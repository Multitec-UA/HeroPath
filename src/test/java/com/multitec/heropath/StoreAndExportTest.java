package com.multitec.heropath;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static com.multitec.heropath.Sample.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class StoreAndExportTest {

    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    static long at(LocalDate d, int h, int m) {
        return d.atTime(h, m).atZone(MADRID).toInstant().toEpochMilli();
    }

    @Test
    void appendReadAndFileByLocalDay(@TempDir Path tmp) throws Exception {
        HistoryStore st = new HistoryStore(tmp, MADRID);
        LocalDate d = LocalDate.of(2026, 10, 3);
        // 23:59 and 00:01 local are different days even though both are 22:xx UTC.
        Set<LocalDate> touched = st.append(ANA, List.of(
                new Sample(at(d, 23, 59), POINT, "world", 1, 64, 1),
                new Sample(at(d.plusDays(1), 0, 1), POINT, "world", 2, 64, 2)));
        assertEquals(Set.of(d, d.plusDays(1)), touched);
        assertEquals(List.of(d, d.plusDays(1)), st.days());
        assertEquals(1, st.read(d).get(ANA).size());

        // A torn last line (crash mid-write) is skipped, the rest still reads.
        Path f = tmp.resolve(d.toString()).resolve(ANA + ".tsv");
        Files.writeString(f, Files.readString(f) + "1791000000000\tP\twor");
        assertEquals(1, st.read(d).get(ANA).size());
    }

    @Test
    void namesSurviveARestart(@TempDir Path tmp) {
        HistoryStore a = new HistoryStore(tmp, MADRID);
        a.rememberName(ANA, "ana");
        a.saveNames();
        assertEquals("ana", new HistoryStore(tmp, MADRID).nameOf(ANA));
    }

    @Test
    void pruneOnlyWhenAsked(@TempDir Path tmp) {
        HistoryStore st = new HistoryStore(tmp, MADRID);
        LocalDate today = LocalDate.of(2026, 10, 3);
        st.append(ANA, List.of(new Sample(at(today.minusDays(40), 12, 0), POINT, "world", 0, 0, 0)));
        st.append(ANA, List.of(new Sample(at(today, 12, 0), POINT, "world", 0, 0, 0)));
        assertEquals(0, st.prune(today, 0), "0 keeps everything");
        assertEquals(2, st.days().size());
        assertEquals(1, st.prune(today, 30));
        assertEquals(List.of(today), st.days());
    }

    @Test
    void exportWritesTheWindowAndDropsOlderWebDays(@TempDir Path tmp) throws Exception {
        HistoryStore st = new HistoryStore(tmp.resolve("history"), MADRID);
        st.rememberName(ANA, "ana");
        LocalDate today = LocalDate.of(2026, 10, 3);
        for (int back : new int[]{0, 1, 5}) {
            LocalDate d = today.minusDays(back);
            st.append(ANA, List.of(
                    new Sample(at(d, 10, 0), JOIN, "world", 0, 64, 0),
                    new Sample(at(d, 10, 1), POINT, "world", 10, 64, 0)));
        }
        Path web = tmp.resolve("web");
        Files.createDirectories(web.resolve("days/2020-01-01"));   // stale, must go
        WebExporter ex = new WebExporter(st, web, 3, w -> w.equals("world") ? List.of("world") : List.of());

        assertEquals(2, ex.export(Set.of(), today), "first export primes the 3-day window: today and yesterday");
        assertTrue(Files.isRegularFile(web.resolve("days/2026-10-03/world.json")));
        assertTrue(Files.isRegularFile(web.resolve("days/2026-10-02/world.json")));
        assertFalse(Files.exists(web.resolve("days/2026-09-28")), "outside the window");
        assertFalse(Files.exists(web.resolve("days/2020-01-01")));

        JsonObject idx = JsonParser.parseString(Files.readString(web.resolve("index.json"))).getAsJsonObject();
        assertEquals("2026-10-03", idx.getAsJsonArray("days").get(0).getAsJsonObject().get("date").getAsString());
        assertEquals("Europe/Madrid", idx.get("tz").getAsString());

        // Later exports touch only dirty days, and keep the others in the index.
        st.append(ANA, List.of(new Sample(at(today, 11, 0), POINT, "world", 20, 64, 0)));
        assertEquals(1, ex.export(Set.of(today), today));
        idx = JsonParser.parseString(Files.readString(web.resolve("index.json"))).getAsJsonObject();
        assertEquals(2, idx.getAsJsonArray("days").size());
        try (var s = Files.walk(web)) {
            assertTrue(s.noneMatch(p -> p.toString().endsWith(".tmp")), "no temp file left behind");
        }
    }
}
