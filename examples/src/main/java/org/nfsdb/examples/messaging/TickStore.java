/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (c) 2014-2016 Appsicle
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package org.nfsdb.examples.messaging;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.LifecycleAware;
import com.questdb.JournalWriter;
import com.questdb.ex.JournalException;
import com.questdb.ex.JournalRuntimeException;
import com.questdb.factory.JournalFactory;

import java.util.concurrent.CountDownLatch;

public class TickStore implements EventHandler<Tick>, LifecycleAware {

    private final JournalFactory factory;
    private final CountDownLatch latch;
    private JournalWriter<Tick> writer;

    public TickStore(JournalFactory factory, CountDownLatch latch) {
        this.factory = factory;
        this.latch = latch;
    }

    @Override
    public void onEvent(Tick event, long sequence, boolean endOfBatch) throws Exception {
        switch (event.instrument) {
            case -1:
                latch.countDown();
                break;
            default:
                event.timestamp = System.currentTimeMillis();
                writer.append(event);
        }
        if (endOfBatch) {
            writer.commit();
        }
    }

    @Override
    public void onStart() {
        try {
            this.writer = factory.bulkWriter(Tick.class);
        } catch (JournalException e) {
            throw new JournalRuntimeException(e);
        }
    }

    @Override
    public void onShutdown() {
        this.writer.close();
    }
}
