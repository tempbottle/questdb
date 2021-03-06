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

package com.questdb.misc;

import com.questdb.ex.NetworkError;
import com.questdb.ex.NumericException;
import com.questdb.std.CharSink;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public final class Net {

    public static final int EWOULDBLOCK;

    public static final int ERETRY = 0;
    public static final int EPEERDISCONNECT = -1;
    @SuppressWarnings("unused")
    public static final int EOTHERDISCONNECT = -2;

    private Net() {
    }

    public native static long accept(long fd);

    public static void appendIP4(CharSink sink, long ip) {
        sink.put(ip & 0xff).put('.').put((ip >> 8) & 0xff).put('.').put((ip >> 16) & 0xff).put('.').put((ip >> 24) & 0xff);
    }

    public native static long available(long fd);

    public native static boolean bind(long fd, int address, int port);

    public static boolean bind(long fd, CharSequence address, int port) {
        return bind(fd, parseIPv4(address), port);
    }

    public static native int configureNonBlocking(long fd);

    public native static long getPeerIP(long fd);

    public native static int getPeerPort(long fd);

    public static native boolean isDead(long fd);

    public native static void listen(long fd, int backlog);

    public static native int recv(long fd, long ptr, int len);

    public static native int send(long fd, long ptr, int len);

    public native static int setRcvBuf(long fd, int size);

    public native static int setSndBuf(long fd, int size);

    public native static long socketTcp(boolean blocking);

    private native static int getEwouldblock();

    @SuppressFBWarnings("LEST_LOST_EXCEPTION_STACK_TRACE")
    private static int parseIPv4(CharSequence address) {
        int ip = 0;
        int count = 0;
        int lo = 0;
        int hi;
        try {
            while ((hi = Chars.indexOf(address, lo, '.')) > -1) {
                int n = Numbers.parseInt(address, lo, hi);
                ip = (ip << 8) | n;
                count++;
                lo = hi + 1;
            }

            if (count != 3) {
                throw new NetworkError("Invalid ip address: " + address);
            }

            return (ip << 8) | Numbers.parseInt(address, lo, address.length());
        } catch (NumericException e) {
            throw new NetworkError("Invalid ip address: " + address);
        }
    }

    static {
        EWOULDBLOCK = getEwouldblock();
    }
}
