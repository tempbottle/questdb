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

package com.questdb.std;

import com.questdb.misc.Os;
import com.questdb.misc.Unsafe;

import java.io.Closeable;

public final class Path extends AbstractCharSequence implements Closeable, LPSZ {
    private long ptr = 0;
    private int capacity;
    private int len;

    public Path() {
        alloc(128);
    }

    public Path(CharSequence str) {
        int l = str.length();
        alloc(l);
        copy(str, l);
    }

    @Override
    public long address() {
        return ptr;
    }

    @Override
    public void close() {
        if (ptr != 0) {
            Unsafe.getUnsafe().freeMemory(ptr);
            ptr = 0;
        }
    }

    @Override
    public int length() {
        return len;
    }

    @Override
    public char charAt(int index) {
        return (char) Unsafe.getUnsafe().getByte(ptr + index);
    }

    public Path of(CharSequence str) {
        int l = str.length();
        if (l >= capacity) {
            Unsafe.getUnsafe().freeMemory(ptr);
            alloc(l);
        }
        copy(str, l);
        this.len = l;
        return this;
    }

    private void alloc(int len) {
        this.capacity = len;
        this.ptr = Unsafe.getUnsafe().allocateMemory(len + 1);
    }

    private void copy(CharSequence str, int len) {
        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            Unsafe.getUnsafe().putByte(ptr + i, (byte) (Os.type == Os.WINDOWS && c == '/' ? '\\' : c));
        }
        Unsafe.getUnsafe().putByte(ptr + len, (byte) 0);
    }
}
