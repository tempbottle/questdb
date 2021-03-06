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

package com.questdb.net.http.handlers;

import com.questdb.ex.DisconnectedChannelException;
import com.questdb.ex.ResponseContentBufferTooSmallException;
import com.questdb.ex.SlowWritableChannelException;
import com.questdb.factory.JournalFactoryPool;
import com.questdb.factory.configuration.RecordColumnMetadata;
import com.questdb.misc.Numbers;
import com.questdb.net.http.ChunkedResponse;
import com.questdb.net.http.ContextHandler;
import com.questdb.net.http.IOContext;
import com.questdb.net.http.ServerConfiguration;
import com.questdb.ql.Record;
import com.questdb.std.CharSink;
import com.questdb.std.LocalValue;
import com.questdb.store.ColumnType;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

public class QueryHandler implements ContextHandler {

    private final JournalFactoryPool factoryPool;
    private final LocalValue<QueryHandlerContext> localContext = new LocalValue<>();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final ServerConfiguration configuration;

    public QueryHandler(JournalFactoryPool factoryPool, ServerConfiguration configuration) {
        this.factoryPool = factoryPool;
        this.configuration = configuration;
    }

    @Override
    public void handle(IOContext context) throws IOException {
        QueryHandlerContext ctx = localContext.get(context);
        if (ctx == null) {
            localContext.set(context,
                    ctx = new QueryHandlerContext(context.channel.getFd(), context.getServerConfiguration().getDbCyclesBeforeCancel()));
        }
        ChunkedResponse r = context.chunkedResponse();
        if (ctx.parseUrl(r, context.request)) {
            ctx.compileQuery(r, factoryPool, cacheMisses, cacheHits);
            resume(context);
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public void resume(IOContext context) throws IOException {
        QueryHandlerContext ctx = localContext.get(context);
        if (ctx == null || ctx.cursor == null) {
            return;
        }

        final ChunkedResponse r = context.chunkedResponse();
        final int columnCount = ctx.metadata.getColumnCount();

        OUT:
        while (true) {
            try {
                SWITCH:
                switch (ctx.state) {
                    case PREFIX:
                        if (ctx.noMeta) {
                            r.put("{\"result\":[");
                            ctx.state = QueryHandlerContext.QueryState.RECORD_START;
                            break;
                        }
                        r.bookmark();
                        r.put('{').putQuoted("query").put(':').putUtf8EscapedAndQuoted(ctx.query);
                        r.put(',').putQuoted("columns").put(':').put('[');
                        ctx.state = QueryHandlerContext.QueryState.METADATA;
                        ctx.columnIndex = 0;
                        // fall through
                    case METADATA:
                        for (; ctx.columnIndex < columnCount; ctx.columnIndex++) {
                            RecordColumnMetadata column = ctx.metadata.getColumnQuick(ctx.columnIndex);

                            r.bookmark();

                            if (ctx.columnIndex > 0) {
                                r.put(',');
                            }
                            r.put('{').
                                    putQuoted("name").put(':').putQuoted(column.getName()).
                                    put(',').
                                    putQuoted("type").put(':').putQuoted(column.getType().name());
                            r.put('}');
                        }
                        ctx.state = QueryHandlerContext.QueryState.META_SUFFIX;
                        // fall through
                    case META_SUFFIX:
                        r.bookmark();
                        r.put("],\"result\":[");
                        ctx.state = QueryHandlerContext.QueryState.RECORD_START;
                        // fall through
                    case RECORD_START:

                        if (ctx.record == null) {
                            // check if cursor has any records
                            while (true) {
                                if (ctx.cursor.hasNext()) {
                                    ctx.record = ctx.cursor.next();
                                    ctx.count++;

                                    if (ctx.fetchAll && ctx.count > ctx.stop) {
                                        continue;
                                    }

                                    if (ctx.count > ctx.skip) {
                                        break;
                                    }
                                } else {
                                    ctx.state = QueryHandlerContext.QueryState.DATA_SUFFIX;
                                    break SWITCH;
                                }
                            }
                        }

                        if (ctx.count > ctx.stop) {
                            ctx.state = QueryHandlerContext.QueryState.DATA_SUFFIX;
                            break;
                        }

                        r.bookmark();
                        if (ctx.count > ctx.skip + 1) {
                            r.put(',');
                        }
                        r.put('[');

                        ctx.state = QueryHandlerContext.QueryState.RECORD_COLUMNS;
                        ctx.columnIndex = 0;
                        // fall through
                    case RECORD_COLUMNS:

                        for (; ctx.columnIndex < columnCount; ctx.columnIndex++) {
                            RecordColumnMetadata m = ctx.metadata.getColumnQuick(ctx.columnIndex);
                            r.bookmark();
                            if (ctx.columnIndex > 0) {
                                r.put(',');
                            }
                            putValue(r, m.getType(), ctx.record, ctx.columnIndex);
                        }

                        ctx.state = QueryHandlerContext.QueryState.RECORD_SUFFIX;
                        // fall through

                    case RECORD_SUFFIX:
                        r.bookmark();
                        r.put(']');
                        ctx.record = null;
                        ctx.state = QueryHandlerContext.QueryState.RECORD_START;
                        break;
                    case DATA_SUFFIX:
                        sendDone(r, ctx);
                        break OUT;
                    default:
                        break OUT;
                }
            } catch (ResponseContentBufferTooSmallException ignored) {
                if (r.resetToBookmark()) {
                    r.sendChunk();
                } else {
                    // what we have here is out unit of data, column value or query
                    // is larger that response content buffer
                    // all we can do in this scenario is to log appropriately
                    // and disconnect socket
                    ctx.info().$("Response buffer is too small, state=").$(ctx.state).$();
                    throw DisconnectedChannelException.INSTANCE;
                }
            }
        }
    }

    @Override
    public void setupThread() {
        AbstractQueryContext.setupThread(configuration);
    }

    private static void putValue(CharSink sink, ColumnType type, Record rec, int col) {
        switch (type) {
            case BOOLEAN:
                sink.put(rec.getBool(col));
                break;
            case BYTE:
                sink.put(rec.get(col));
                break;
            case DOUBLE:
                sink.putJson(rec.getDouble(col), 10);
                break;
            case FLOAT:
                sink.putJson(rec.getFloat(col), 10);
                break;
            case INT:
                final int i = rec.getInt(col);
                if (i == Integer.MIN_VALUE) {
                    sink.put("null");
                    break;
                }
                Numbers.append(sink, i);
                break;
            case LONG:
                final long l = rec.getLong(col);
                if (l == Long.MIN_VALUE) {
                    sink.put("null");
                    break;
                }
                sink.put(l);
                break;
            case DATE:
                final long d = rec.getDate(col);
                if (d == Long.MIN_VALUE) {
                    sink.put("null");
                    break;
                }
                sink.put('"').putISODate(d).put('"');
                break;
            case SHORT:
                sink.put(rec.getShort(col));
                break;
            case STRING:
                putStringOrNull(sink, rec.getFlyweightStr(col));
                break;
            case SYMBOL:
                putStringOrNull(sink, rec.getSym(col));
                break;
            case BINARY:
                sink.put('[');
                sink.put(']');
                break;
            default:
                break;
        }
    }

    private static void putStringOrNull(CharSink r, CharSequence str) {
        if (str == null) {
            r.put("null");
        } else {
            r.putUtf8EscapedAndQuoted(str);
        }
    }

    long getCacheHits() {
        return cacheHits.longValue();
    }

    long getCacheMisses() {
        return cacheMisses.longValue();
    }

    private void sendDone(ChunkedResponse r, QueryHandlerContext ctx) throws DisconnectedChannelException, SlowWritableChannelException {
        if (ctx.count > -1) {
            r.bookmark();
            r.put(']');
            r.put(',').putQuoted("count").put(':').put(ctx.count);
            r.put('}');
            ctx.count = -1;
            r.sendChunk();
        }
        r.done();
    }

}
