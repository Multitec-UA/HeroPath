package com.multitec.heropath;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;

/**
 * Turns one day of history into the JSON the web map reads. Pure: no Bukkit, no files.
 *
 * <p>One document per BlueMap map. A Minecraft world can be shown by several maps, so a
 * world's samples are copied to each of them; samples in a world no map shows are dropped.
 *
 * <pre>
 * {"date":"2026-10-03","map":"multitecworld","players":[
 *   {"uuid":"…","name":"yupipi93","color":"#33ccff","from":36000,"to":39600,
 *    "segments":[[[36000,10.5,64,-3],[36005,12,64,-3]], …],
 *    "events":[[37000,"D",11,60,-2], …]}]}
 * </pre>
 *
 * Times are seconds since local midnight of {@code date}, which keeps the files small and
 * is what the time slider shows. A segment is a run of positions with nothing between
 * them that was not walked: it starts at a join or a jump and ends at a quit.
 */
public final class DayExport {

    public record PlayerDay(UUID uuid, String name, int color, long from, long to, Set<String> maps) {
    }

    public record Result(Map<String, String> jsonByMap, List<PlayerDay> players) {
    }

    private DayExport() {
    }

    public static Result build(LocalDate date, ZoneId zone,
                               Map<UUID, List<Sample>> samples,
                               Function<UUID, String> names,
                               Function<String, Collection<String>> mapsOfWorld) {
        long midnight = date.atStartOfDay(zone).toInstant().toEpochMilli();
        Map<String, StringBuilder> docs = new TreeMap<>();
        Map<String, Boolean> firstPlayer = new HashMap<>();
        List<PlayerDay> players = new ArrayList<>();

        List<UUID> uuids = new ArrayList<>(samples.keySet());
        uuids.sort(Comparator.comparing(u -> names.apply(u).toLowerCase(Locale.ROOT)));

        for (UUID uuid : uuids) {
            List<Sample> list = new ArrayList<>(samples.get(uuid));
            if (list.isEmpty()) continue;
            list.sort(Comparator.comparingLong(Sample::time));

            // world -> segments / events, built in time order
            Map<String, List<List<Sample>>> segs = new LinkedHashMap<>();
            Map<String, List<Sample>> events = new LinkedHashMap<>();
            List<Sample> current = null;
            String currentWorld = null;
            for (Sample s : list) {
                boolean newWorld = !s.world().equals(currentWorld);
                if (current == null || newWorld || s.kind().breaksSegment()) {
                    current = new ArrayList<>();
                    segs.computeIfAbsent(s.world(), w -> new ArrayList<>()).add(current);
                    currentWorld = s.world();
                }
                current.add(s);
                if (s.kind() != Sample.Kind.POINT && s.kind() != Sample.Kind.JUMP) {
                    events.computeIfAbsent(s.world(), w -> new ArrayList<>()).add(s);
                }
                if (s.kind() == Sample.Kind.QUIT) {
                    current = null;
                }
            }

            String name = names.apply(uuid);
            int color = Colors.forPlayer(uuid);
            long from = secs(list.get(0).time(), midnight);
            long to = secs(list.get(list.size() - 1).time(), midnight);
            Set<String> playerMaps = new TreeSet<>();

            for (Map.Entry<String, List<List<Sample>>> e : segs.entrySet()) {
                Collection<String> maps = mapsOfWorld.apply(e.getKey());
                if (maps == null || maps.isEmpty()) continue;
                String body = playerJson(uuid, name, color, e.getValue(),
                        events.getOrDefault(e.getKey(), List.of()), midnight);
                if (body == null) continue;
                for (String map : maps) {
                    StringBuilder doc = docs.computeIfAbsent(map, m -> new StringBuilder()
                            .append("{\"date\":").append(Json.str(date.toString()))
                            .append(",\"map\":").append(Json.str(m))
                            .append(",\"players\":["));
                    if (firstPlayer.put(map, false) != null) doc.append(',');
                    doc.append(body);
                    playerMaps.add(map);
                }
            }
            if (!playerMaps.isEmpty()) {
                players.add(new PlayerDay(uuid, name, color, from, to, playerMaps));
            }
        }

        Map<String, String> out = new TreeMap<>();
        docs.forEach((map, b) -> out.put(map, b.append("]}").toString()));
        return new Result(out, players);
    }

    private static String playerJson(UUID uuid, String name, int color, List<List<Sample>> segments,
                                     List<Sample> events, long midnight) {
        StringBuilder b = new StringBuilder();
        long from = Long.MAX_VALUE, to = Long.MIN_VALUE;
        StringBuilder segJson = new StringBuilder("[");
        boolean firstSeg = true;
        for (List<Sample> seg : segments) {
            if (seg.isEmpty()) continue;
            if (!firstSeg) segJson.append(',');
            firstSeg = false;
            segJson.append('[');
            for (int i = 0; i < seg.size(); i++) {
                Sample s = seg.get(i);
                long t = secs(s.time(), midnight);
                from = Math.min(from, t);
                to = Math.max(to, t);
                if (i > 0) segJson.append(',');
                segJson.append('[').append(t).append(',').append(Json.num(s.x())).append(',')
                        .append(Json.num(s.y())).append(',').append(Json.num(s.z())).append(']');
            }
            segJson.append(']');
        }
        segJson.append(']');
        if (firstSeg) return null;

        StringBuilder evJson = new StringBuilder("[");
        for (int i = 0; i < events.size(); i++) {
            Sample s = events.get(i);
            if (i > 0) evJson.append(',');
            evJson.append('[').append(secs(s.time(), midnight)).append(',')
                    .append(Json.str(String.valueOf(s.kind().code))).append(',')
                    .append(Json.num(s.x())).append(',').append(Json.num(s.y())).append(',')
                    .append(Json.num(s.z())).append(']');
        }
        evJson.append(']');

        b.append("{\"uuid\":").append(Json.str(uuid.toString()))
                .append(",\"name\":").append(Json.str(name))
                .append(",\"color\":").append(Json.str(Colors.hex(color)))
                .append(",\"from\":").append(from)
                .append(",\"to\":").append(to)
                .append(",\"segments\":").append(segJson)
                .append(",\"events\":").append(evJson)
                .append('}');
        return b.toString();
    }

    static long secs(long epochMillis, long midnightMillis) {
        return Math.max(0, (epochMillis - midnightMillis) / 1000);
    }

    /** The index the web map loads first: which days exist, who played, on which maps. */
    public static String indexJson(String zone, long generated, SortedMap<LocalDate, List<PlayerDay>> days) {
        StringBuilder b = new StringBuilder("{\"tz\":").append(Json.str(zone))
                .append(",\"generated\":").append(generated).append(",\"days\":[");
        boolean firstDay = true;
        for (Map.Entry<LocalDate, List<PlayerDay>> e : days.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (!firstDay) b.append(',');
            firstDay = false;
            b.append("{\"date\":").append(Json.str(e.getKey().toString())).append(",\"players\":[");
            boolean firstP = true;
            for (PlayerDay p : e.getValue()) {
                if (!firstP) b.append(',');
                firstP = false;
                b.append("{\"uuid\":").append(Json.str(p.uuid().toString()))
                        .append(",\"name\":").append(Json.str(p.name()))
                        .append(",\"color\":").append(Json.str(Colors.hex(p.color())))
                        .append(",\"from\":").append(p.from())
                        .append(",\"to\":").append(p.to())
                        .append(",\"maps\":[");
                boolean firstM = true;
                for (String m : p.maps()) {
                    if (!firstM) b.append(',');
                    firstM = false;
                    b.append(Json.str(m));
                }
                b.append("]}");
            }
            b.append("]}");
        }
        return b.append("]}").toString();
    }
}
