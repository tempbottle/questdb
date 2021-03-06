/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2016 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.ql.impl.join.asof;

import com.questdb.ex.JournalRuntimeException;
import com.questdb.factory.configuration.RecordColumnMetadata;
import com.questdb.factory.configuration.RecordMetadata;
import com.questdb.misc.Numbers;
import com.questdb.misc.Unsafe;
import com.questdb.ql.Record;
import com.questdb.ql.RecordCursor;
import com.questdb.ql.StorageFacade;
import com.questdb.ql.impl.LongMetadata;
import com.questdb.ql.impl.map.MapValues;
import com.questdb.ql.impl.map.MultiMap;
import com.questdb.std.*;
import com.questdb.store.ColumnType;
import com.questdb.store.SymbolTable;

public class LastFixRecordMap implements LastRecordMap {
    private static final ObjList<RecordColumnMetadata> valueMetadata = new ObjList<>();
    private final MultiMap map;
    private final LongList pages = new LongList();
    private final int pageSize;
    private final IntHashSet slaveKeyIndexes;
    private final IntHashSet masterKeyIndexes;
    private final IntList slaveValueIndexes;
    private final ObjList<ColumnType> slaveKeyTypes;
    private final ObjList<ColumnType> masterKeyTypes;
    private final ObjList<ColumnType> slaveValueTypes;
    private final IntList fixedOffsets;
    private final int recordLen;
    private final RecordMetadata metadata;
    private final MapRecord record;
    private final int bits;
    private final int mask;
    private StorageFacade storageFacade;
    private long appendOffset;

    public LastFixRecordMap(
            RecordMetadata masterMetadata,
            RecordMetadata slaveMetadata,
            @Transient CharSequenceHashSet masterKeyColumns,
            @Transient CharSequenceHashSet slaveKeyColumns,
            int dataPageSize,
            int offsetPageSize) {
        this.pageSize = Numbers.ceilPow2(dataPageSize);
        this.bits = Numbers.msb(this.pageSize);
        this.mask = this.pageSize - 1;

        final int ksz = masterKeyColumns.size();
        this.masterKeyTypes = new ObjList<>(ksz);
        this.slaveKeyTypes = new ObjList<>(ksz);
        this.masterKeyIndexes = new IntHashSet(ksz);
        this.slaveKeyIndexes = new IntHashSet(ksz);

        // collect key field indexes for slave
        ObjHashSet<String> keyCols = new ObjHashSet<>(ksz);

        for (int i = 0; i < ksz; i++) {
            int idx;
            idx = masterMetadata.getColumnIndex(masterKeyColumns.get(i));
            masterKeyTypes.add(masterMetadata.getColumnQuick(idx).getType());
            masterKeyIndexes.add(idx);

            idx = slaveMetadata.getColumnIndex(slaveKeyColumns.get(i));
            slaveKeyIndexes.add(idx);
            slaveKeyTypes.add(slaveMetadata.getColumnQuick(idx).getType());
            keyCols.add(slaveMetadata.getColumnName(idx));
        }

        int sz = ksz - keyCols.size();
        this.fixedOffsets = new IntList(sz);
        this.slaveValueIndexes = new IntList(sz);
        this.slaveValueTypes = new ObjList<>(sz);

        int varOffset = 0;
        // collect indexes of non-key fields in slave record
        for (int i = 0, n = slaveMetadata.getColumnCount(); i < n; i++) {
            fixedOffsets.add(varOffset);
            slaveValueIndexes.add(i);
            ColumnType type = slaveMetadata.getColumnQuick(i).getType();
            slaveValueTypes.add(type);
            varOffset += type.size();
        }

        if (varOffset > dataPageSize) {
            throw new JournalRuntimeException("Record size is too large");
        }

        this.recordLen = varOffset;
        this.map = new MultiMap(offsetPageSize, slaveMetadata, keyCols, valueMetadata, null);
        this.metadata = slaveMetadata;
        this.record = new MapRecord(this.metadata);
    }

    @Override
    public void close() {
        for (int i = 0; i < pages.size(); i++) {
            Unsafe.getUnsafe().freeMemory(pages.getQuick(i));
        }
        pages.clear();
        map.close();
    }

    public Record get(Record master) {
        long offset;
        MapValues values = getByMaster(master);
        if (values == null || (((offset = values.getLong(0)) & SET_BIT) == SET_BIT)) {
            return null;
        }
        values.putLong(0, offset | SET_BIT);
        return record.of(pages.getQuick(pageIndex(offset)) + pageOffset(offset));
    }

    public RecordMetadata getMetadata() {
        return metadata;
    }

    @Override
    public StorageFacade getStorageFacade() {
        return storageFacade;
    }

    public void put(Record record) {
        final MapValues values = getBySlave(record);
        // new record, append right away
        if (values.isNew()) {
            appendRec(record, values);
        } else {
            // old record, attempt to overwrite
            writeRec(record, values.getLong(0) & CLR_BIT, values);
        }
    }

    @Override
    public void reset() {
        appendOffset = 0;
        map.clear();
    }

    @Override
    public void setSlaveCursor(RecordCursor cursor) {
        this.storageFacade = cursor.getStorageFacade();
    }

    private void appendRec(Record record, MapValues values) {
        int pgInx = pageIndex(appendOffset);
        int pgOfs = pageOffset(appendOffset);

        if (pgOfs + recordLen > pageSize) {
            pgInx++;
            pgOfs = 0;
            values.putLong(0, appendOffset = (pgInx * pageSize));
        } else {
            values.putLong(0, appendOffset);
        }
        appendOffset += recordLen;
        // allocateOffset if necessary
        if (pgInx == pages.size()) {
            pages.add(Unsafe.getUnsafe().allocateMemory(pageSize));
        }
        writeRec0(pages.getQuick(pgInx) + pgOfs, record);
    }

    private MapValues getByMaster(Record record) {
        return map.getValues(RecordUtils.createKey(map, record, masterKeyIndexes, masterKeyTypes));
    }

    private MapValues getBySlave(Record record) {
        return map.getOrCreateValues(RecordUtils.createKey(map, record, slaveKeyIndexes, slaveKeyTypes));
    }

    private int pageIndex(long offset) {
        return (int) (offset >> bits);
    }

    private int pageOffset(long offset) {
        return (int) (offset & mask);
    }

    private void writeRec(Record record, long offset, MapValues values) {
        values.putLong(0, offset);
        writeRec0(pages.getQuick(pageIndex(offset)) + pageOffset(offset), record);
    }

    private void writeRec0(long addr, Record record) {
        for (int i = 0, n = slaveValueIndexes.size(); i < n; i++) {
            RecordUtils.copyFixed(slaveValueTypes.getQuick(i), record, slaveValueIndexes.getQuick(i), addr + fixedOffsets.getQuick(i));
        }
    }

    private class MapRecord extends AbstractMemRecord {
        private long address;

        MapRecord(RecordMetadata metadata) {
            super(metadata);
        }

        @Override
        protected long address(int col) {
            return address + fixedOffsets.getQuick(col);
        }

        @Override
        protected SymbolTable getSymbolTable(int col) {
            return storageFacade.getSymbolTable(col);
        }

        private MapRecord of(long address) {
            this.address = address;
            return this;
        }
    }

    static {
        valueMetadata.add(LongMetadata.INSTANCE);
    }
}
