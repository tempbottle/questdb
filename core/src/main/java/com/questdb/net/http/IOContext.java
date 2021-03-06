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

package com.questdb.net.http;

import com.questdb.ex.DisconnectedChannelException;
import com.questdb.ex.SlowWritableChannelException;
import com.questdb.iter.clock.Clock;
import com.questdb.misc.Misc;
import com.questdb.net.NetworkChannel;
import com.questdb.net.NonBlockingSecureSocketChannel;
import com.questdb.std.LocalValueMap;
import com.questdb.std.Locality;
import com.questdb.std.Mutable;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;

public class IOContext implements Closeable, Mutable, Locality {
    public final NetworkChannel channel;
    public final Request request;
    private final ServerConfiguration serverConfiguration;
    private final LocalValueMap map = new LocalValueMap();
    private final Response response;
    private final AtomicBoolean open = new AtomicBoolean(true);

    public IOContext(NetworkChannel channel, ServerConfiguration configuration, Clock clock) {
        this.channel = configuration.getSslConfig().isSecure() ?
                new NonBlockingSecureSocketChannel(channel, configuration.getSslConfig()) :
                channel;
        this.serverConfiguration = configuration;
        this.request = new Request(this.channel, configuration);
        this.response = new Response(this.channel, configuration, clock);
    }

    public ChunkedResponse chunkedResponse() {
        return response.asChunked();
    }

    @Override
    public void clear() {
        request.clear();
        response.clear();
        map.clear();
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            // !!! it is important not to close request before closing local value map !!!
            Misc.free(map);
            Misc.free(channel);
            Misc.free(request);
            Misc.free(response);
        }
    }

    public SimpleResponse emergencyResponse() {
        response.clear();
        return response.asSimple();
    }

    public FixedSizeResponse fixedSizeResponse() {
        return response.asFixedSize();
    }

    @Override
    public LocalValueMap getMap() {
        return map;
    }

    public ServerConfiguration getServerConfiguration() {
        return serverConfiguration;
    }

    public ResponseSink responseSink() {
        return response.asSink();
    }

    public void resume() throws DisconnectedChannelException, SlowWritableChannelException {
        response.resume();
    }

    public SimpleResponse simpleResponse() {
        return response.asSimple();
    }

    int getResponseCode() {
        return response.getCode();
    }
}
