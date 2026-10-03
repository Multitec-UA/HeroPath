package com.multitec.heropath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Writes the history the web map reads into BlueMap's webroot:
 *
 * <pre>
 * assets/heropath/index.json                 which days exist and who played (newest first)
 * assets/heropath/days/&lt;date&gt;/&lt;map&gt;.json      one day of one map
 * </pre>
 *
 * Everything here is derived from {@link HistoryStore} and can be thrown away: only the last
 * {@code webDays} days are published, older day folders are removed, and the whole tree is
 * rebuilt on the first export after a start. Each file is written to a temp name and moved
 * into place, so the publisher never copies half a file.
 *
 * <p>Not thread-safe; used only from the plugin's single IO thread.
 */
public final class WebExporter {

    private final HistoryStore store;
    private final Path dir;
    private final int webDays;
    private final Function<String, Collection<String>> mapsOfWorld;
    private final SortedMap<LocalDate, List<DayExport.PlayerDay>> index =
            new TreeMap<>(Comparator.reverseOrder());
    private boolean primed;

    public WebExporter(HistoryStore store, Path webAssetsDir, int webDays,
                       Function<String, Collection<String>> mapsOfWorld) {
        this.store = store;
        this.dir = webAssetsDir;
        this.webDays = Math.max(1, webDays);
        this.mapsOfWorld = mapsOfWorld;
    }

    /**
     * Re-exports the given days (plus every day in the window on the first call) and rewrites
     * the index. Returns the number of day files written.
     */
    public int export(Set<LocalDate> dirtyDays, LocalDate today) {
        LocalDate oldest = today.minusDays(webDays - 1L);
        Set<LocalDate> todo = new TreeSet<>(dirtyDays);
        if (!primed) {
            for (LocalDate d : store.days()) if (!d.isBefore(oldest)) todo.add(d);
            primed = true;
        }
        int written = 0;
        for (LocalDate day : todo) {
            if (day.isBefore(oldest) || day.isAfter(today)) continue;
            DayExport.Result r = DayExport.build(day, store.zone(), store.read(day), store::nameOf, mapsOfWorld);
            Path dayDir = dir.resolve("days").resolve(day.toString());
            HistoryStore.deleteTree(dayDir);
            for (Map.Entry<String, String> e : r.jsonByMap().entrySet()) {
                writeAtomic(dayDir.resolve(safe(e.getKey()) + ".json"), e.getValue());
                written++;
            }
            index.put(day, r.players());
        }
        index.keySet().removeIf(d -> d.isBefore(oldest));
        removeOldDayDirs(oldest);
        writeAtomic(dir.resolve("index.json"),
                DayExport.indexJson(store.zone().getId(), System.currentTimeMillis(), index));
        return written;
    }

    private void removeOldDayDirs(LocalDate oldest) {
        Path days = dir.resolve("days");
        if (!Files.isDirectory(days)) return;
        try (Stream<Path> s = Files.list(days)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                try {
                    if (LocalDate.parse(p.getFileName().toString()).isBefore(oldest)) HistoryStore.deleteTree(p);
                } catch (Exception ignored) {
                    // not ours
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A BlueMap map id is already [a-z0-9_-]; this only guards against a surprise. */
    static String safe(String mapId) {
        return mapId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    static void writeAtomic(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
