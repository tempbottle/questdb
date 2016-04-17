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
 * As a special exception, the copyright holders give permission to link the
 * code of portions of this program with the OpenSSL library under certain
 * conditions as described in each individual source file and distribute
 * linked combinations including the program with the OpenSSL library. You
 * must comply with the GNU Affero General Public License in all respects for
 * all of the code used other than as permitted herein. If you modify file(s)
 * with this exception, you may extend this exception to your version of the
 * file(s), but you are not obligated to do so. If you do not wish to do so,
 * delete this exception statement from your version. If you delete this
 * exception statement from all source files in the program, then also delete
 * it in the license file.
 *
 ******************************************************************************/

package com.nfsdb.ql.impl.aggregation;


import com.nfsdb.ex.JournalException;
import com.nfsdb.factory.JournalReaderFactory;
import com.nfsdb.factory.configuration.RecordColumnMetadata;
import com.nfsdb.factory.configuration.RecordMetadata;
import com.nfsdb.misc.Misc;
import com.nfsdb.misc.Unsafe;
import com.nfsdb.ql.*;
import com.nfsdb.ql.impl.join.hash.KeyWriterHelper;
import com.nfsdb.ql.impl.map.MapRecordValueInterceptor;
import com.nfsdb.ql.impl.map.MapValues;
import com.nfsdb.ql.impl.map.MultiMap;
import com.nfsdb.ql.ops.AbstractCombinedRecordSource;
import com.nfsdb.std.*;
import com.nfsdb.std.ThreadLocal;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.Closeable;
import java.io.IOException;

@SuppressFBWarnings({"LII_LIST_INDEXED_ITERATING"})
public class AggregatedRecordSource extends AbstractCombinedRecordSource implements Closeable {

    private static final ThreadLocal<ObjList<RecordColumnMetadata>> tlColumns = new ThreadLocal<>(new ObjectFactory<ObjList<RecordColumnMetadata>>() {
        @Override
        public ObjList<RecordColumnMetadata> newInstance() {
            return new ObjList<>();
        }
    });

    private final MultiMap map;
    private final RecordSource recordSource;
    private final int[] keyIndices;
    private final ObjList<AggregatorFunction> aggregators;
    private RecordCursor recordCursor;
    private RecordCursor mapRecordSource;

    @SuppressFBWarnings({"LII_LIST_INDEXED_ITERATING"})
    public AggregatedRecordSource(
            RecordSource recordSource,
            @Transient ObjHashSet<String> keyColumns,
            ObjList<AggregatorFunction> aggregators
    ) {
        int keyColumnsSize = keyColumns.size();
        this.keyIndices = new int[keyColumnsSize];

        RecordMetadata rm = recordSource.getMetadata();
        for (int i = 0; i < keyColumnsSize; i++) {
            keyIndices[i] = rm.getColumnIndex(keyColumns.get(i));
        }

        this.aggregators = aggregators;

        ObjList<MapRecordValueInterceptor> interceptors = new ObjList<>();
        ObjList<RecordColumnMetadata> columns = tlColumns.get();
        columns.clear();

        // take value columns from aggregator function
        int index = 0;
        for (int i = 0, sz = aggregators.size(); i < sz; i++) {
            AggregatorFunction func = aggregators.getQuick(i);
            int n = columns.size();
            func.prepare(columns, index);
            index += columns.size() - n;

            if (func instanceof MapRecordValueInterceptor) {
                interceptors.add((MapRecordValueInterceptor) func);
            }
        }
        this.map = new MultiMap(rm, keyColumns, columns, interceptors);
        this.recordSource = recordSource;
    }

    @Override
    public void close() throws IOException {
        Misc.free(this.map);
        Misc.free(recordSource);
    }

    @Override
    public Record getByRowId(long rowId) {
        return null;
    }

    @Override
    public StorageFacade getStorageFacade() {
        return recordCursor.getStorageFacade();
    }

    @Override
    public RecordMetadata getMetadata() {
        return map.getMetadata();
    }

    @Override
    public RecordCursor prepareCursor(JournalReaderFactory factory) throws JournalException {
        this.recordCursor = recordSource.prepareCursor(factory);
        buildMap();
        return this;
    }

    @Override
    public void reset() {
        recordSource.reset();
        map.clear();
    }

    @Override
    public boolean supportsRowIdAccess() {
        return false;
    }

    @Override
    public boolean hasNext() {
        return mapRecordSource.hasNext();
    }

    @Override
    public Record next() {
        return mapRecordSource.next();
    }

    private void buildMap() {

        while (recordCursor.hasNext()) {

            Record rec = recordCursor.next();

            // we are inside of time window, compute aggregates
            MultiMap.KeyWriter keyWriter = map.keyWriter();
            for (int i = 0; i < keyIndices.length; i++) {
                int index;
                KeyWriterHelper.setKey(
                        keyWriter,
                        rec,
                        index = Unsafe.arrayGet(keyIndices, i),
                        recordSource.getMetadata().getColumnQuick(index).getType()
                );
            }

            MapValues values = map.getOrCreateValues(keyWriter);

            for (int i = 0, sz = aggregators.size(); i < sz; i++) {
                aggregators.getQuick(i).calculate(rec, values);
            }
        }
        mapRecordSource = map.getCursor();
    }
}