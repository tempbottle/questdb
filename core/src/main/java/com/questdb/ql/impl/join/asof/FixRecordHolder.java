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

import com.questdb.factory.configuration.RecordMetadata;
import com.questdb.misc.Unsafe;
import com.questdb.ql.Record;
import com.questdb.ql.RecordCursor;
import com.questdb.ql.StorageFacade;
import com.questdb.std.IntList;
import com.questdb.std.ObjList;
import com.questdb.store.ColumnType;
import com.questdb.store.SymbolTable;

public class FixRecordHolder extends AbstractMemRecord implements RecordHolder {
    private final ObjList<ColumnType> types;
    private final IntList offsets;
    private long address;
    private StorageFacade storageFacade;
    private boolean held = false;

    public FixRecordHolder(RecordMetadata metadata) {
        super(metadata);

        int cc = metadata.getColumnCount();
        this.types = new ObjList<>(cc);
        this.offsets = new IntList(cc);

        int size = 0;
        for (int i = 0; i < cc; i++) {
            ColumnType type = metadata.getColumnQuick(i).getType();
            types.add(type);
            offsets.add(size);

            switch (type) {
                case INT:
                case FLOAT:
                case SYMBOL:
                    size += 4;
                    break;
                case LONG:
                case DOUBLE:
                case DATE:
                    size += 8;
                    break;
                case BOOLEAN:
                case BYTE:
                    size++;
                    break;
                case SHORT:
                    size += 2;
                    break;
                default:
                    break;
            }
        }
        address = Unsafe.getUnsafe().allocateMemory(size);
    }

    @Override
    public void clear() {
        held = false;
    }

    @Override
    public Record peek() {
        return held ? this : null;
    }

    @Override
    public void setCursor(RecordCursor cursor) {
        this.storageFacade = cursor.getStorageFacade();
    }

    public void write(Record record) {
        this.held = true;
        for (int i = 0, n = types.size(); i < n; i++) {
            RecordUtils.copyFixed(types.getQuick(i), record, i, this.address + offsets.getQuick(i));
        }
    }

    @Override
    public void close() {
        if (address != 0) {
            Unsafe.getUnsafe().freeMemory(address);
            address = 0;
        }
    }

    protected long address(int col) {
        return address + offsets.getQuick(col);
    }

    @Override
    protected SymbolTable getSymbolTable(int col) {
        return storageFacade.getSymbolTable(col);
    }

}
