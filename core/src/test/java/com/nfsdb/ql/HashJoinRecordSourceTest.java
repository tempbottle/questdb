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

package com.nfsdb.ql;

import com.nfsdb.JournalWriter;
import com.nfsdb.ex.JournalConfigurationException;
import com.nfsdb.ex.JournalRuntimeException;
import com.nfsdb.factory.configuration.JournalConfigurationBuilder;
import com.nfsdb.io.RecordSourcePrinter;
import com.nfsdb.io.sink.StringSink;
import com.nfsdb.misc.Files;
import com.nfsdb.model.Album;
import com.nfsdb.model.Band;
import com.nfsdb.model.Quote;
import com.nfsdb.ql.impl.AllRowSource;
import com.nfsdb.ql.impl.JournalPartitionSource;
import com.nfsdb.ql.impl.JournalSource;
import com.nfsdb.ql.impl.join.HashJoinRecordSource;
import com.nfsdb.ql.impl.select.SelectedColumnsRecordSource;
import com.nfsdb.std.IntList;
import com.nfsdb.std.ObjList;
import com.nfsdb.test.tools.JournalTestFactory;
import com.nfsdb.test.tools.TestUtils;
import org.junit.*;

public class HashJoinRecordSourceTest {
    @Rule
    public final JournalTestFactory factory;
    private JournalWriter<Band> bw;
    private JournalWriter<Album> aw;

    public HashJoinRecordSourceTest() {
        try {
            this.factory = new JournalTestFactory(
                    new JournalConfigurationBuilder() {{
                        $(Band.class).$ts();
                        $(Album.class).$ts("releaseDate");

                    }}.build(Files.makeTempDir())
            );
        } catch (JournalConfigurationException e) {
            throw new JournalRuntimeException(e);
        }
    }

    @Before
    public void setUp() throws Exception {
        bw = factory.writer(Band.class);
        aw = factory.writer(Album.class);
    }

    @Test
    public void testHashJoinJournalRecordSource() throws Exception {
        bw.append(new Band().setName("band1").setType("rock").setUrl("http://band1.com"));
        bw.append(new Band().setName("band2").setType("blues").setUrl("http://band2.com"));
        bw.append(new Band().setName("band3").setType("jazz").setUrl("http://band3.com"));
        bw.append(new Band().setName("band1").setType("jazz").setUrl("http://new.band1.com"));

        bw.commit();

        aw.append(new Album().setName("album X").setBand("band1").setGenre("pop"));
        aw.append(new Album().setName("album Y").setBand("band3").setGenre("metal"));
        aw.append(new Album().setName("album BZ").setBand("band1").setGenre("rock"));

        aw.commit();

        StringSink sink = new StringSink();
        RecordSourcePrinter p = new RecordSourcePrinter(sink);
        RecordSource joinResult = new SelectedColumnsRecordSource(
                new HashJoinRecordSource(
                        new JournalSource(new JournalPartitionSource(bw.getMetadata(), false), new AllRowSource()),
                        new IntList() {{
                            add(bw.getMetadata().getColumnIndex("name"));
                        }},
                        new JournalSource(new JournalPartitionSource(aw.getMetadata(), false), new AllRowSource()),
                        new IntList() {{
                            add(aw.getMetadata().getColumnIndex("band"));
                        }},
                        false
                ),
                new ObjList<CharSequence>() {{
                    add("genre");
                }}
        );
        p.printCursor(joinResult.prepareCursor(factory));
        Assert.assertEquals("pop\n" +
                "rock\n" +
                "metal\n" +
                "pop\n" +
                "rock\n", sink.toString());
    }

    @Test
    @Ignore
    public void testHashJoinPerformance() throws Exception {
        final JournalWriter<Quote> w1 = factory.writer(Quote.class, "q1");
        TestUtils.generateQuoteData(w1, 100000);

        final JournalWriter<Quote> w2 = factory.writer(Quote.class, "q2");
        TestUtils.generateQuoteData(w2, 100000);

        RecordSource j = new HashJoinRecordSource(
                new JournalSource(new JournalPartitionSource(w1.getMetadata(), false), new AllRowSource()),
                new IntList() {{
                    w1.getMetadata().getColumnIndex("sym");
                }},
                new JournalSource(new JournalPartitionSource(w2.getMetadata(), false), new AllRowSource()),
                new IntList() {{
                    w2.getMetadata().getColumnIndex("sym");
                }},
                false
        );

        long t = System.currentTimeMillis();
        int count = 0;
//        ExportManager.export(j, new File("c:/temp/join.csv"), TextFileFormat.TAB);
        RecordCursor c = j.prepareCursor(factory);
        while (c.hasNext()) {
            c.next();
            count++;
        }
        System.out.println(System.currentTimeMillis() - t);
        System.out.println(count);


//        ExportManager.export(factory, "q1", new File("d:/q1.csv"), TextFileFormat.TAB);
//        ExportManager.export(factory, "q2", new File("d:/q2.csv"), TextFileFormat.TAB);
//        ExportManager.export(j, new File("d:/join.csv"), TextFileFormat.TAB);
    }

    @Test
    public void testHashJoinRecordSource() throws Exception {
        bw.append(new Band().setName("band1").setType("rock").setUrl("http://band1.com"));
        bw.append(new Band().setName("band2").setType("blues").setUrl("http://band2.com"));
        bw.append(new Band().setName("band3").setType("jazz").setUrl("http://band3.com"));
        bw.append(new Band().setName("band1").setType("jazz").setUrl("http://new.band1.com"));

        bw.commit();

        aw.append(new Album().setName("album X").setBand("band1").setGenre("pop"));
        aw.append(new Album().setName("album Y").setBand("band3").setGenre("metal"));
        aw.append(new Album().setName("album BZ").setBand("band1").setGenre("rock"));

        aw.commit();

        StringSink sink = new StringSink();
        RecordSourcePrinter p = new RecordSourcePrinter(sink);
        RecordSource joinResult = new SelectedColumnsRecordSource(
                new HashJoinRecordSource(
                        new JournalSource(new JournalPartitionSource(bw.getMetadata(), false), new AllRowSource()),
                        new IntList() {{
                            add(bw.getMetadata().getColumnIndex("name"));
                        }},
                        new JournalSource(new JournalPartitionSource(aw.getMetadata(), false), new AllRowSource()),
                        new IntList() {{
                            add(aw.getMetadata().getColumnIndex("band"));
                        }},
                        false
                ),
                new ObjList<CharSequence>() {{
                    add("genre");
                }}
        );
        p.printCursor(joinResult.prepareCursor(factory));
        Assert.assertEquals("pop\n" +
                "rock\n" +
                "metal\n" +
                "pop\n" +
                "rock\n", sink.toString());
    }

    @Test
    public void testOuterHashJoin() throws Exception {
        bw.append(new Band().setName("band1").setType("rock").setUrl("http://band1.com"));
        bw.append(new Band().setName("band2").setType("blues").setUrl("http://band2.com"));
        bw.append(new Band().setName("band3").setType("jazz").setUrl("http://band3.com"));
        bw.append(new Band().setName("band1").setType("jazz").setUrl("http://new.band1.com"));
        bw.append(new Band().setName("band5").setType("jazz").setUrl("http://new.band5.com"));

        bw.commit();

        aw.append(new Album().setName("album X").setBand("band1").setGenre("pop"));
        aw.append(new Album().setName("album Y").setBand("band3").setGenre("metal"));
        aw.append(new Album().setName("album BZ").setBand("band1").setGenre("rock"));

        aw.commit();

        StringSink sink = new StringSink();
        RecordSourcePrinter p = new RecordSourcePrinter(sink);
        RecordSource joinResult = new SelectedColumnsRecordSource(
                new HashJoinRecordSource(
                        new JournalSource(new JournalPartitionSource(bw.getMetadata(), false), new AllRowSource()),
                        new IntList() {{
                            add(bw.getMetadata().getColumnIndex("name"));
                        }},
                        new JournalSource(new JournalPartitionSource(aw.getMetadata(), false), new AllRowSource()),
                        new IntList() {{
                            add(aw.getMetadata().getColumnIndex("band"));
                        }},
                        true
                ),
                new ObjList<CharSequence>() {{
                    add("genre");
                    add("url");
                }}
        );
        p.printCursor(joinResult.prepareCursor(factory));
        Assert.assertEquals("pop\thttp://band1.com\n" +
                "rock\thttp://band1.com\n" +
                "\thttp://band2.com\n" +
                "metal\thttp://band3.com\n" +
                "pop\thttp://new.band1.com\n" +
                "rock\thttp://new.band1.com\n" +
                "\thttp://new.band5.com\n", sink.toString());
    }
}