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

package com.questdb.ql.impl;

import com.questdb.ex.JournalRuntimeException;
import com.questdb.factory.configuration.RecordMetadata;
import com.questdb.misc.Chars;
import com.questdb.misc.Unsafe;
import com.questdb.ql.Record;
import com.questdb.ql.RecordCursor;
import com.questdb.ql.StorageFacade;
import com.questdb.std.AbstractImmutableIterator;
import com.questdb.std.DirectInputStream;
import com.questdb.std.Mutable;
import com.questdb.store.MemoryPages;

import java.io.Closeable;

public class RecordList extends AbstractImmutableIterator<Record> implements Closeable, RecordCursor, Mutable {
    private final MemoryPages mem;
    private final RecordListRecord record;
    private final RecordMetadata metadata;
    private final int columnCount;
    private final int headerSize;
    private final int recordPrefix;
    private long readAddress = -1;
    private StorageFacade storageFacade;

    public RecordList(RecordMetadata recordMetadata, int pageSize) {
        this.metadata = recordMetadata;
        this.mem = new MemoryPages(pageSize);
        this.record = new RecordListRecord(recordMetadata, mem);
        this.headerSize = record.getHeaderSize();
        this.columnCount = recordMetadata.getColumnCount();
        this.recordPrefix = headerSize + record.getFixedSize();
    }

    public long append(Record record, long prevAddress) {
        long thisAddress = mem.allocate(8 + recordPrefix) + 8;
        if (prevAddress != -1) {
            Unsafe.getUnsafe().putLong(prevAddress - 8, thisAddress);
        }
        Unsafe.getUnsafe().putLong(thisAddress - 8, -1L);
        append0(record, thisAddress);
        return thisAddress;
    }

    public void clear() {
        mem.clear();
        readAddress = -1;
    }

    @Override
    public void close() {
        mem.close();
    }

    @Override
    public Record getByRowId(long rowId) {
        record.of(rowId);
        return record;
    }

    @Override
    public RecordMetadata getMetadata() {
        return metadata;
    }

    @Override
    public StorageFacade getStorageFacade() {
        return storageFacade;
    }

    public void setStorageFacade(StorageFacade storageFacade) {
        record.setStorageFacade(this.storageFacade = storageFacade);
    }

    @Override
    public boolean hasNext() {
        return readAddress > -1;
    }

    @Override
    public Record next() {
        record.of(readAddress);
        readAddress = Unsafe.getUnsafe().getLong(readAddress - 8);
        return record;
    }

    public RecordListRecord newRecord() {
        return new RecordListRecord(metadata, mem);
    }

    public void of(long offset) {
        this.readAddress = offset;
    }

    public void toTop() {
        readAddress = mem.addressOf(0) + 8;
    }

    private void append0(Record record, long address) {
        long headerAddress = address;
        long writeAddress = headerAddress + headerSize;

        for (int i = 0, n = columnCount; i < n; i++) {
            switch (metadata.getColumnQuick(i).getType()) {
                case BOOLEAN:
                    Unsafe.getUnsafe().putByte(writeAddress, (byte) (record.getBool(i) ? 1 : 0));
                    writeAddress += 1;
                    break;
                case BYTE:
                    Unsafe.getUnsafe().putByte(writeAddress, record.get(i));
                    writeAddress += 1;
                    break;
                case DOUBLE:
                    Unsafe.getUnsafe().putDouble(writeAddress, record.getDouble(i));
                    writeAddress += 8;
                    break;
                case INT:
                    Unsafe.getUnsafe().putInt(writeAddress, record.getInt(i));
                    writeAddress += 4;
                    break;
                case LONG:
                    Unsafe.getUnsafe().putLong(writeAddress, record.getLong(i));
                    writeAddress += 8;
                    break;
                case SHORT:
                    Unsafe.getUnsafe().putShort(writeAddress, record.getShort(i));
                    writeAddress += 2;
                    break;
                case SYMBOL:
                    Unsafe.getUnsafe().putInt(writeAddress, record.getInt(i));
                    writeAddress += 4;
                    break;
                case DATE:
                    Unsafe.getUnsafe().putLong(writeAddress, record.getDate(i));
                    writeAddress += 8;
                    break;
                case FLOAT:
                    Unsafe.getUnsafe().putFloat(writeAddress, record.getFloat(i));
                    writeAddress += 4;
                    break;
                case STRING:
                    writeString(headerAddress, record.getFlyweightStr(i));
                    headerAddress += 8;
                    break;
                case BINARY:
                    writeBin(headerAddress, record.getBin(i));
                    headerAddress += 8;
                    break;
                default:
                    throw new JournalRuntimeException("Unsupported type: " + metadata.getColumnQuick(i).getType());
            }
        }
    }

    private void writeBin(long headerAddress, DirectInputStream value) {
        long offset = mem.allocateOffset(8);
        long len = value.size();

        // Write header offset.
        Unsafe.getUnsafe().putLong(headerAddress, offset);

        // Write length.
        Unsafe.getUnsafe().putLong(mem.addressOf(offset), len);

        if (len < 1) {
            return;
        }

        offset = mem.allocateOffset(1);
        long p = 0;
        do {
            int remaining = mem.pageRemaining(offset);
            int sz = remaining < len ? remaining : (int) len;
            value.copyTo(mem.addressOf(offset), p, sz);
            p += sz;
            mem.allocateOffset(sz);
            offset += sz;
            len -= sz;
        } while (len > 0);
    }

    private void writeNullString(long headerAddress) {
        long addr = mem.allocate(4);
        Unsafe.getUnsafe().putLong(headerAddress, addr);
        Unsafe.getUnsafe().putInt(addr, -1);
    }

    private void writeString(long headerAddress, CharSequence value) {
        if (value == null) {
            writeNullString(headerAddress);
            return;
        }

        // Allocate.
        final int l = value.length();
        if (l > mem.pageSize()) {
            throw new JournalRuntimeException("String larger than pageSize");
        }
        long addr = mem.allocate(l * 2 + 4);
        // Save the address in the header.
        Unsafe.getUnsafe().putLong(headerAddress, addr);
        Chars.put(addr, value);
    }

}
