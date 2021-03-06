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

package com.questdb.net;

import com.questdb.ex.NetworkError;
import com.questdb.misc.Files;
import com.questdb.misc.Os;
import com.questdb.misc.Unsafe;

import java.io.Closeable;

public final class Epoll implements Closeable {
    public static final short SIZEOF_EVENT;
    public static final int EPOLLIN;
    public static final int EPOLLOUT;
    public static final int EPOLL_CTL_ADD;
    public static final int EPOLL_CTL_MOD;
    private static final short DATA_OFFSET;
    private static final short EVENTS_OFFSET;
    private static final int EPOLLONESHOT;
    private static final int EPOLLET;
    private final long events;
    private final long epfd;
    private final int capacity;
    private boolean closed = false;
    private long _rPtr;

    public Epoll(int capacity) {
        this.capacity = capacity;
        this.events = _rPtr = Unsafe.getUnsafe().allocateMemory(SIZEOF_EVENT * (long) capacity);
        this.epfd = epollCreate();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        Files.close(epfd);
        Unsafe.getUnsafe().freeMemory(events);
        closed = true;
    }

    public int control(long fd, long id, int cmd, int event) {
        Unsafe.getUnsafe().putInt(events + EVENTS_OFFSET, event | EPOLLET | EPOLLONESHOT);
        Unsafe.getUnsafe().putLong(events + DATA_OFFSET, id);
        return epollCtl(epfd, cmd, fd, events);
    }

    public long getData() {
        return Unsafe.getUnsafe().getLong(_rPtr + DATA_OFFSET);
    }

    public int getEvent() {
        return Unsafe.getUnsafe().getInt(_rPtr + EVENTS_OFFSET);
    }

// --Commented out by Inspection START (15/05/2016, 01:07):
//    public long getFd() {
//        return Unsafe.getUnsafe().getInt(_rPtr + DATA_OFFSET);
//    }
// --Commented out by Inspection STOP (15/05/2016, 01:07)

    public void listen(long sfd) {
        Unsafe.getUnsafe().putInt(events + EVENTS_OFFSET, EPOLLIN | EPOLLET);
        Unsafe.getUnsafe().putLong(events + DATA_OFFSET, 0);

        if (epollCtl(epfd, EPOLL_CTL_ADD, sfd, events) < 0) {
            throw new NetworkError("Error in epoll_ctl: " + Os.errno());
        }
    }

    public int poll() {
        return epollWait(epfd, events, capacity, 0);
    }

    public void setOffset(int offset) {
        this._rPtr = this.events + (long) offset;
    }

    private static native long epollCreate();

    private static native int epollCtl(long epfd, int op, long fd, long eventPtr);

    private static native int epollWait(long epfd, long eventPtr, int eventCount, int timeout);

    private static native short getDataOffset();

    private static native short getEventsOffset();

    private static native short getEventSize();

    private static native int getEPOLLIN();

    private static native int getEPOLLET();

    private static native int getEPOLLOUT();

    private static native int getEPOLLONESHOT();

    private static native int getCtlAdd();

    private static native int getCtlMod();

    static {
        Os.init();
        DATA_OFFSET = getDataOffset();
        EVENTS_OFFSET = getEventsOffset();
        SIZEOF_EVENT = getEventSize();
        EPOLLIN = getEPOLLIN();
        EPOLLET = getEPOLLET();
        EPOLLOUT = getEPOLLOUT();
        EPOLLONESHOT = getEPOLLONESHOT();
        EPOLL_CTL_ADD = getCtlAdd();
        EPOLL_CTL_MOD = getCtlMod();
    }
}
