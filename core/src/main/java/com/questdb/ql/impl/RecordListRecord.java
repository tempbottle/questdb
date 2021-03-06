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
import com.questdb.misc.Unsafe;
import com.questdb.ql.AbstractRecord;
import com.questdb.ql.StorageFacade;
import com.questdb.std.CharSink;
import com.questdb.std.DirectCharSequence;
import com.questdb.std.DirectInputStream;
import com.questdb.store.ColumnType;
import com.questdb.store.MemoryPages;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.io.OutputStream;

public class RecordListRecord extends AbstractRecord {
    private final DirectCharSequence csA = new DirectCharSequence();
    private final DirectCharSequence csB = new DirectCharSequence();
    private final MemoryPages mem;
    private final int headerSize;
    private final int[] offsets;
    private int fixedSize;
    private long address;
    private StorageFacade storageFacade;

    public RecordListRecord(RecordMetadata metadata, MemoryPages mem) {
        super(metadata);
        this.mem = mem;
        offsets = new int[metadata.getColumnCount()];

        int varColIndex = 0;
        for (int i = 0; i < offsets.length; i++) {
            ColumnType ct = metadata.getColumnQuick(i).getType();
            if (ct.size() != 0) {
                // Fixed columns.
                offsets[i] = fixedSize;
                fixedSize += ct.size();
            } else {
                offsets[i] = -(varColIndex++);
            }
        }

        // Pad header size to 8 bytes.
        fixedSize = ((fixedSize + 7) >> 3) << 3;
        headerSize = varColIndex * 8;
    }

    @Override
    public byte get(int col) {
        assert offsets[col] >= 0;
        return Unsafe.getUnsafe().getByte(address + Unsafe.arrayGet(offsets, col));
    }

    @SuppressFBWarnings({"EXS_EXCEPTION_SOFTENING_NO_CHECKED"})
    @Override
    public void getBin(int col, OutputStream s) {
        final long readOffset = offsetOf(col);
        final long readAddress = mem.addressOf(readOffset);
        try {
            long len = Unsafe.getUnsafe().getLong(readAddress);
            if (len > 0) {
                streamBin(s, readAddress + 8, len);
            }
        } catch (IOException ex) {
            throw new JournalRuntimeException("Reading binary column failed", ex);
        }
    }

    @Override
    public DirectInputStream getBin(int col) {
        final long offset = offsetOf(col);
        final long len = Unsafe.getUnsafe().getLong(mem.addressOf(offset));
        if (len < 0) return null;
        return new DirectPagedBufferStream(mem, offset + 8, len);
    }

    @Override
    public long getBinLen(int col) {
        return Unsafe.getUnsafe().getLong(mem.addressOf(offsetOf(col)));
    }

    @Override
    public boolean getBool(int col) {
        assert offsets[col] >= 0;
        return Unsafe.getBool(address + Unsafe.arrayGet(offsets, col));
    }

    @Override
    public long getDate(int col) {
        assert offsets[col] >= 0;
        return Unsafe.getUnsafe().getLong(address + Unsafe.arrayGet(offsets, col));
    }

    @Override
    public double getDouble(int col) {
        assert offsets[col] >= 0;
        return Unsafe.getUnsafe().getDouble(address + Unsafe.arrayGet(offsets, col));
    }

    @Override
    public float getFloat(int col) {
        assert offsets[col] >= 0;
        return Unsafe.getUnsafe().getFloat(address + Unsafe.arrayGet(offsets, col));
    }

    @Override
    public CharSequence getFlyweightStr(int col) {
        long readAddress = addressOf(col);
        final int len = Unsafe.getUnsafe().getInt(readAddress);
        if (len < 0) return null;
        return csA.of(readAddress + 4, readAddress + 4 + len * 2);
    }

    @Override
    public CharSequence getFlyweightStrB(int col) {
        long readAddress = addressOf(col);
        final int len = Unsafe.getUnsafe().getInt(readAddress);
        if (len < 0) return null;
        return csB.of(readAddress + 4, readAddress + 4 + len * 2);
    }

    @Override
    public int getInt(int col) {
        assert offsets[col] >= 0;
        return Unsafe.getUnsafe().getInt(address + Unsafe.arrayGet(offsets, col));
    }

    @Override
    public long getLong(int col) {
        assert offsets[col] >= 0;
        return Unsafe.getUnsafe().getLong(address + Unsafe.arrayGet(offsets, col));
    }

    @Override
    public long getRowId() {
        return address - headerSize;
    }

    @Override
    public short getShort(int col) {
        assert offsets[col] >= 0;
        return Unsafe.getUnsafe().getShort(address + Unsafe.arrayGet(offsets, col));
    }

    @Override
    public CharSequence getStr(int col) {
        long readAddress = addressOf(col);
        final int len = Unsafe.getUnsafe().getInt(readAddress);
        if (len < 0) return null;
        return new DirectCharSequence().of(readAddress + 4, readAddress + 4 + len * 2);
    }

    @Override
    public void getStr(int col, CharSink sink) {
        long readAddress = addressOf(col);
        final int len = Unsafe.getUnsafe().getInt(readAddress);
        readAddress += 2;
        for (int i = 0; i < len; i++) {
            sink.put(Unsafe.getUnsafe().getChar(readAddress += 2));
        }
    }

    @Override
    public int getStrLen(int col) {
        return Unsafe.getUnsafe().getInt(addressOf(col));
    }

    @Override
    public String getSym(int col) {
        return storageFacade.getSymbolTable(col).value(getInt(col));
    }

    public int getFixedSize() {
        return fixedSize;
    }

    public int getHeaderSize() {
        return headerSize;
    }

    public void of(long address) {
        this.address = address + headerSize;
    }

    public void setStorageFacade(StorageFacade storageFacade) {
        this.storageFacade = storageFacade;
    }

    private long addressOf(int index) {
        return offsetOf(index);
    }

    private long offsetOf(int index) {
        // Not fixed len.
        assert offsets[index] <= 0;
        return Unsafe.getUnsafe().getLong(address - headerSize + (-Unsafe.arrayGet(offsets, index)) * 8);
    }

    private void streamBin(OutputStream stream, long offset, final long len) throws IOException {
        long position = offset;
        long copied = 0;
        long l = len;
        while (copied < l) {
            long blockEndOffset = mem.pageRemaining(offset);
            long copyEndOffset = Math.min(blockEndOffset, l - copied);
            l += copyEndOffset - position;
            while (position < copyEndOffset) {
                stream.write(Unsafe.getUnsafe().getByte(mem.addressOf(offset) + position++));
            }
        }
    }

    private static class DirectPagedBufferStream extends DirectInputStream {
        private final long length;
        private final MemoryPages buffer;
        private final long offset;
        private long blockStartAddress;
        private long blockEndOffset;
        private long blockStartOffset;
        private long position;

        private DirectPagedBufferStream(MemoryPages buffer, long offset, long length) {
            this.buffer = buffer;
            this.offset = offset;
            this.blockStartAddress = buffer.addressOf(offset);
            this.blockStartOffset = 0;
            this.length = length;
        }

        @Override
        public long copyTo(final long address, long start, long len) {
            if (start < 0 || len < 0) {
                throw new IndexOutOfBoundsException();
            }

            long res;
            long rem = this.length - start;
            long size = res = len > rem ? rem : len;
            long offset = this.offset + start;

            long p = address;
            do {
                int remaining = buffer.pageRemaining(offset);
                int sz = size > remaining ? remaining : (int) size;
                Unsafe.getUnsafe().copyMemory(buffer.addressOf(offset), p, sz);
                p += sz;
                offset += sz;
                size -= sz;
            } while (size > 0);

            return res;
        }

        @Override
        public long size() {
            return (int) length - position;
        }

        @Override
        public int read() throws IOException {
            if (position < length) {
                if (position < blockEndOffset) {
                    return Unsafe.getUnsafe().getByte(blockStartAddress + offset + position++ - blockStartOffset);
                }
                return readFromNextBlock();
            }
            return -1;
        }

        private int readFromNextBlock() {
            blockStartOffset = offset + position;
            blockStartAddress = buffer.addressOf(blockStartOffset);
            long blockLen = buffer.pageRemaining(blockStartOffset);
            if (blockLen < 0) {
                return -1;
            }

            blockEndOffset += blockLen;
            assert position < blockEndOffset;
            return Unsafe.getUnsafe().getByte(blockStartAddress + offset + position++ - blockStartOffset);
        }

    }
}
