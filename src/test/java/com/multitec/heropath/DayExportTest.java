package com.multitec.heropath;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static com.multitec.heropath.Sample.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class DayExportTest {

    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate DAY = LocalDate.of(2026, 10, 3);
    static final long MIDNIGHT = DAY.atStartOfDay(MADRID).toInstant().toEpochMilli();
    static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID BEA = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    static Sample s(long secs, Sample.Kind k, String w, double x, double z) {
        return new Sample(MIDNIGHT + secs * 1000, k, w, x, 64, z);
    }

    static final Map<String, Collection<String>> MAPS = Map.of(
            "world", List.of("world", "world_flat"),
            "world_nether", List.of("world_nether"));

    static DayExport.Result build(Map<UUID, List<Sample>> samples) {
        return DayExport.build(DAY, MADRID, samples,
                u -> u.equals(ANA) ? "ana" : "bea",
                w -> MAPS.getOrDefault(w, List.of()));
    }

    @Test
    void segmentsBreakAtJumpsAndQuitsAndEventsAreKept() {
        Map<UUID, List<Sample>> in = Map.of(ANA, List.of(
                s(36000, JOIN, "world", 0, 0),
                s(36005, POINT, "world", 5, 0),
                s(36010, DEATH, "world", 9, 0),
                s(36020, JUMP, "world", 100, 100),     // respawn
                s(36025, POINT, "world", 103, 100),
                s(36030, QUIT, "world", 104, 100),
                s(40000, JOIN, "world", 104, 100)));
        DayExport.Result r = build(in);

        JsonObject doc = JsonParser.parseString(r.jsonByMap().get("world")).getAsJsonObject();
        JsonObject ana = doc.getAsJsonArray("players").get(0).getAsJsonObject();
        JsonArray segs = ana.getAsJsonArray("segments");
        assertEquals(3, segs.size(), "join..death | respawn..quit | join");
        assertEquals(3, segs.get(0).getAsJsonArray().size());
        assertEquals(3, segs.get(1).getAsJsonArray().size());
        assertEquals(36000, segs.get(0).getAsJsonArray().get(0).getAsJsonArray().get(0).getAsInt(),
                "times are seconds since local midnight");

        JsonArray ev = ana.getAsJsonArray("events");
        List<String> kinds = new ArrayList<>();
        ev.forEach(e -> kinds.add(e.getAsJsonArray().get(1).getAsString()));
        assertEquals(List.of("J", "D", "Q", "J"), kinds, "POINT and JUMP are not events");
        assertEquals(36000, ana.get("from").getAsInt());
        assertEquals(40000, ana.get("to").getAsInt());
        assertTrue(ana.get("color").getAsString().matches("#[0-9a-f]{6}"));
    }

    @Test
    void aWorldShownByTwoMapsGoesToBothAndWorldsDoNotMix() {
        Map<UUID, List<Sample>> in = Map.of(ANA, List.of(
                s(100, JOIN, "world", 0, 0),
                s(105, POINT, "world", 5, 0),
                s(110, JUMP, "world_nether", 1, 1),
                s(115, POINT, "world_nether", 4, 1),
                s(120, JUMP, "unmapped_world", 0, 0)));
        DayExport.Result r = build(in);
        assertEquals(Set.of("world", "world_flat", "world_nether"), r.jsonByMap().keySet(),
                "a world no map shows is dropped");
        assertEquals(r.jsonByMap().get("world").replace("\"map\":\"world\"", ""),
                r.jsonByMap().get("world_flat").replace("\"map\":\"world_flat\"", ""));
        JsonObject nether = JsonParser.parseString(r.jsonByMap().get("world_nether")).getAsJsonObject();
        JsonArray segs = nether.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonArray("segments");
        assertEquals(1, segs.size());
        assertEquals(2, segs.get(0).getAsJsonArray().size(), "only the nether samples");
        assertEquals(Set.of("world", "world_flat", "world_nether"), r.players().get(0).maps());
    }

    @Test
    void playersAreSortedByNameAndNamesAreEscaped() {
        Map<UUID, List<Sample>> in = new HashMap<>();
        in.put(BEA, List.of(s(1, JOIN, "world", 0, 0)));
        in.put(ANA, List.of(s(2, JOIN, "world", 0, 0)));
        DayExport.Result r = DayExport.build(DAY, MADRID, in,
                u -> u.equals(ANA) ? "a\"na" : "bea", w -> MAPS.getOrDefault(w, List.of()));
        JsonArray players = JsonParser.parseString(r.jsonByMap().get("world")).getAsJsonObject().getAsJsonArray("players");
        assertEquals("a\"na", players.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("bea", players.get(1).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void theIndexIsValidJsonNewestFirst() {
        SortedMap<LocalDate, List<DayExport.PlayerDay>> days = new TreeMap<>(Comparator.reverseOrder());
        days.put(DAY.minusDays(1), List.of(new DayExport.PlayerDay(ANA, "ana", 0xff0000, 10, 20, new TreeSet<>(Set.of("world")))));
        days.put(DAY, List.of(new DayExport.PlayerDay(BEA, "bea", 0x00ff00, 30, 40, new TreeSet<>(Set.of("world")))));
        days.put(DAY.minusDays(2), List.of());
        JsonObject idx = JsonParser.parseString(DayExport.indexJson("Europe/Madrid", 1L, days)).getAsJsonObject();
        JsonArray d = idx.getAsJsonArray("days");
        assertEquals(2, d.size(), "a day with nobody is left out");
        assertEquals("2026-10-03", d.get(0).getAsJsonObject().get("date").getAsString());
        assertEquals("#ff0000", d.get(1).getAsJsonObject().getAsJsonArray("players").get(0).getAsJsonObject().get("color").getAsString());
    }

    @Test
    void secondsAreLocalAcrossTheDstChange() {
        // 2026-10-25: Madrid goes from UTC+2 to UTC+1, so that day is 25 hours long.
        LocalDate dst = LocalDate.of(2026, 10, 25);
        long midnight = dst.atStartOfDay(MADRID).toInstant().toEpochMilli();
        long lateEvening = dst.atTime(23, 0).atZone(MADRID).toInstant().toEpochMilli();
        assertEquals(24 * 3600, DayExport.secs(lateEvening, midnight),
                "23:00 is 24 h after midnight on a 25-hour day; the slider shows elapsed time");
    }

    @Test
    void eventsCarryTheirDetailAndTheDayItsMidnight() {
        Map<UUID, List<Sample>> in = Map.of(ANA, List.of(
                s(100, JOIN, "world", 0, 0),
                new Sample(MIDNIGHT + 110_000, ADVANCEMENT, "world", 1, 64, 0, "goal|Acquire Hardware"),
                new Sample(MIDNIGHT + 120_000, DEATH, "world", 2, 64, 0, "ana \"fell\""),
                new Sample(MIDNIGHT + 130_000, DIMENSION, "world_nether", 5, 64, 5, "overworld")));
        DayExport.Result r = build(in);
        JsonObject doc = JsonParser.parseString(r.jsonByMap().get("world")).getAsJsonObject();
        assertEquals(MIDNIGHT / 1000, doc.get("t0").getAsLong());
        JsonArray ev = doc.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonArray("events");
        assertTrue(ev.get(0).getAsJsonArray().get(5).isJsonNull(), "a join has no detail");
        assertEquals("goal|Acquire Hardware", ev.get(1).getAsJsonArray().get(5).getAsString());
        assertEquals("ana \"fell\"", ev.get(2).getAsJsonArray().get(5).getAsString(), "quotes survive");
        JsonObject nether = JsonParser.parseString(r.jsonByMap().get("world_nether")).getAsJsonObject();
        JsonArray nev = nether.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonArray("events");
        assertEquals("W", nev.get(0).getAsJsonArray().get(1).getAsString(), "the arrival is drawn in the nether");
    }
}
