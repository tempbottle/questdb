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

package com.questdb.factory.configuration;

import com.questdb.PartitionType;
import com.questdb.ex.JournalConfigurationException;
import com.questdb.misc.ByteBuffers;
import com.questdb.misc.Numbers;
import com.questdb.misc.Unsafe;
import com.questdb.std.CharSequenceIntHashMap;
import com.questdb.std.ObjObjHashMap;
import com.questdb.store.ColumnType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class JournalMetadataBuilder<T> implements MetadataBuilder<T> {
    private final ObjObjHashMap<String, ColumnMetadata> columnMetadata = new ObjObjHashMap<>();
    private final Class<T> modelClass;
    private Constructor<T> constructor;
    private CharSequenceIntHashMap nameToIndexMap;
    private String location;
    private int tsColumnIndex = -1;
    private PartitionType partitionBy = PartitionType.NONE;
    private int recordCountHint = 100000;
    private int txCountHint = -1;
    private String keyColumn;
    private long openFileTTL = TimeUnit.MINUTES.toMillis(3);
    private int lag = -1;

    public JournalMetadataBuilder(Class<T> modelClass) {
        this.modelClass = modelClass;
        parseClass();
    }

    public JournalMetadataBuilder(JournalMetadata<T> model) {
        this.modelClass = model.getModelClass();
        parseClass();
        this.location = model.getLocation();
        this.tsColumnIndex = model.getTimestampIndex();
        this.partitionBy = model.getPartitionType();
        this.recordCountHint = model.getRecordHint();
        this.txCountHint = model.getTxCountHint();
        this.keyColumn = model.getKeyQuiet();
        this.openFileTTL = model.getOpenFileTTL();
        this.lag = model.getLag();
        for (int i = 0, n = model.getColumnCount(); i < n; i++) {
            ColumnMetadata from = model.getColumnQuick(i);
            columnMetadata.get(from.name).copy(from);
        }
    }

    public BinaryBuilder<T> $bin(String name) {
        return new BinaryBuilder<>(this, getMeta(name));
    }

    public JournalMetadataBuilder<T> $date(String name) {
        getMeta(name).type = ColumnType.DATE;
        return this;
    }

    public IntBuilder<T> $int(String name) {
        return new IntBuilder<>(this, getMeta(name));
    }

    public StringBuilder<T> $str(String name) {
        return new StringBuilder<>(this, getMeta(name));
    }

    public SymbolBuilder<T> $sym(String name) {
        return new SymbolBuilder<>(this, getMeta(name));
    }

    public JournalMetadataBuilder<T> $ts() {
        return $ts("timestamp");
    }

    public JournalMetadataBuilder<T> $ts(String name) {
        tsColumnIndex = nameToIndexMap.get(name);
        if (tsColumnIndex == -1) {
            throw new JournalConfigurationException("Invalid column name: %s", name);
        }
        getMeta(name).type = ColumnType.DATE;
        return this;
    }

    public JournalMetadata<T> build() {

        // default tx count hint
        if (txCountHint == -1) {
            txCountHint = (int) (recordCountHint * 0.1);
        }


        ColumnMetadata metadata[] = new ColumnMetadata[nameToIndexMap.size()];

        for (ObjObjHashMap.Entry<String, ColumnMetadata> e : columnMetadata.immutableIterator()) {
            int index = nameToIndexMap.get(e.key);
            ColumnMetadata meta = e.value;


            if (meta.indexed && meta.distinctCountHint < 2) {
                meta.distinctCountHint = Numbers.ceilPow2(Math.max(2, (int) (recordCountHint * 0.01))) - 1;
            }

            if (meta.size == 0 && meta.avgSize == 0) {
                throw new JournalConfigurationException("Invalid size for column %s.%s", modelClass.getName(), meta.name);
            }

            // distinctCount
            if (meta.distinctCountHint < 1 && meta.type == ColumnType.SYMBOL) {
                meta.distinctCountHint = Numbers.ceilPow2((int) (recordCountHint * 0.2)) - 1; //20%
            }

            switch (meta.type) {
                case STRING:
                    meta.size = meta.avgSize + 4;
                    meta.bitHint = ByteBuffers.getBitHint(meta.avgSize * 2, recordCountHint);
                    meta.indexBitHint = ByteBuffers.getBitHint(8, recordCountHint);
                    break;
                case BINARY:
                    meta.size = meta.avgSize;
                    meta.bitHint = ByteBuffers.getBitHint(meta.avgSize, recordCountHint);
                    meta.indexBitHint = ByteBuffers.getBitHint(8, recordCountHint);
                    break;
                default:
                    meta.bitHint = ByteBuffers.getBitHint(meta.size, recordCountHint);
                    break;
            }

            metadata[index] = meta;
        }

        return new JournalMetadata<>(
                modelClass.getName()
                , modelClass
                , constructor
                , keyColumn
                , location
                , partitionBy
                , metadata
                , tsColumnIndex
                , openFileTTL
                , recordCountHint
                , txCountHint
                , lag
                , false
        );
    }

    public String getLocation() {
        return location;
    }

    public JournalMetadataBuilder<T> location(String location) {
        this.location = location;
        return this;
    }

    public JournalMetadataBuilder<T> location(File location) {
        this.location = location.getAbsolutePath();
        return this;
    }

    @Override
    public JournalMetadataBuilder<T> partitionBy(PartitionType type) {
        if (type != PartitionType.DEFAULT) {
            this.partitionBy = type;
        }
        return this;
    }

    @Override
    public JournalMetadataBuilder<T> recordCountHint(int count) {
        if (count > 0) {
            this.recordCountHint = count;
        }
        return this;
    }

    public JournalMetadataBuilder<T> keyColumn(String key) {
        this.keyColumn = key;
        return this;
    }

    public JournalMetadataBuilder<T> lag(long time, TimeUnit unit) {
        this.lag = (int) unit.toHours(time);
        return this;
    }

    public JournalMetadataBuilder<T> openFileTTL(long time, TimeUnit unit) {
        this.openFileTTL = unit.toMillis(time);
        return this;
    }

    public JournalMetadataBuilder<T> txCountHint(int count) {
        this.txCountHint = count;
        return this;
    }

    private List<Field> getAllFields(final List<Field> fields, final Class<?> type) {
        Collections.addAll(fields, type.getDeclaredFields());
        return type.getSuperclass() != null ? getAllFields(fields, type.getSuperclass()) : fields;
    }

    private ColumnMetadata getMeta(String name) {
        ColumnMetadata meta = columnMetadata.get(name);
        if (meta == null) {
            throw new JournalConfigurationException("No such column: %s", name);
        }
        return meta;
    }

    @SuppressFBWarnings({"LEST_LOST_EXCEPTION_STACK_TRACE", "EXS_EXCEPTION_SOFTENING_NO_CONSTRAINTS", "LII_LIST_INDEXED_ITERATING"})
    private void parseClass() throws JournalConfigurationException {
        try {
            this.constructor = modelClass.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new JournalConfigurationException("No default constructor declared on %s", modelClass.getName());
        }

        List<Field> classFields = getAllFields(new ArrayList<Field>(), modelClass);

        this.nameToIndexMap = new CharSequenceIntHashMap(classFields.size());
        this.location = modelClass.getCanonicalName();

        for (int i = 0; i < classFields.size(); i++) {
            Field f = classFields.get(i);

            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            ColumnMetadata meta = new ColumnMetadata();
            Class type = f.getType();
            for (ColumnType t : ColumnType.values()) {
                if (t.matches(type)) {
                    meta.type = t;
                    meta.size = t.size();
                    break;
                }
            }

            if (meta.type == null) {
                continue;
            }

            meta.offset = Unsafe.getUnsafe().objectFieldOffset(f);
            meta.name = f.getName();
            columnMetadata.put(meta.name, meta);
            nameToIndexMap.put(meta.name, nameToIndexMap.size());
        }
    }
}
