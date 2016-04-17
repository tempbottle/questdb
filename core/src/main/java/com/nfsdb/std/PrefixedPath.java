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

package com.nfsdb.std;

import com.nfsdb.misc.Os;
import com.nfsdb.misc.Unsafe;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;

public final class PrefixedPath extends AbstractCharSequence implements Closeable, LPSZ {
    private final int prefixLen;
    private long ptr = 0;
    private int capacity = 0;
    private int len;

    public PrefixedPath(CharSequence prefix) {
        this(prefix, 128);
    }

    PrefixedPath(CharSequence prefix, int minCapacity) {

        int l = prefix.length();

        alloc(Math.max(minCapacity, l * 2));

        for (int i = 0; i < l; i++) {
            char c = prefix.charAt(i);
            Unsafe.getUnsafe().putByte(ptr + i, (byte) (Os.type == Os.WINDOWS && c == '/' ? '\\' : c));
        }

        char c = prefix.charAt(l - 1);
        if (c != '/' && c != '\\') {
            Unsafe.getUnsafe().putByte(ptr + l, (byte) (Os.type == Os.WINDOWS ? '\\' : '/'));
            l++;
        }

        Unsafe.getUnsafe().putByte(ptr + l, (byte) 0);
        this.len = this.prefixLen = l;
    }

    @Override
    public long address() {
        return ptr;
    }

    @Override
    public void close() {
        if (ptr > 0) {
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

    public PrefixedPath of(CharSequence str) {
        int l = str.length();
        if (l + prefixLen > capacity) {
            alloc(l + len);
        }
        long p = ptr + prefixLen;
        for (int i = 0; i < l; i++) {
            char c = str.charAt(i);
            Unsafe.getUnsafe().putByte(p + i, (byte) (Os.type == Os.WINDOWS && c == '/' ? '\\' : c));
        }
        Unsafe.getUnsafe().putByte(p + l, (byte) 0);
        this.len = this.prefixLen + l;
        return this;
    }

    @SuppressFBWarnings("RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE")
    @Override
    @NotNull
    public String toString() {
        if (ptr == 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        long p = this.ptr;
        byte b;
        while ((b = Unsafe.getUnsafe().getByte(p++)) != 0) {
            builder.append((char) b);
        }
        return builder.toString();
    }

    private void alloc(int l) {
        long p = Unsafe.getUnsafe().allocateMemory(l + 1);
        if (ptr > 0) {
            Unsafe.getUnsafe().copyMemory(ptr, p, len);
            Unsafe.getUnsafe().freeMemory(ptr);
        }
        ptr = p;
        this.capacity = l;
    }
}