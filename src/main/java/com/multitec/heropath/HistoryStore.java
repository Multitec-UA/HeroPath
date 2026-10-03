package com.multitec.heropath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Stream;

/**
 * The durable history: {@code <root>/<yyyy-mm-dd>/<uuid>.tsv}, one {@link Sample} per line.
 *
 * <p>Append-only. A day is the server's local day ({@code timezone} in the config), so the
 * file a session goes into matches the day a person would name. Nothing here ever deletes
 * history except {@link #prune}, which only runs when the config sets a retention.
 *
 * <p>Not thread-safe; used only from the plugin's single IO thread.
 */
public final class HistoryStore {

    private final Path root;
    private final ZoneId zone;
    private final Path namesFile;
    private final Map<UUID, String> names = new HashMap<>();
    private boolean namesDirty;

    public HistoryStore(Path root, ZoneId zone) {
        this.root = root;
        this.zone = zone;
        this.namesFile = root.resolve("names.tsv");
        loadNames();
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDate dayOf(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
    }

    /** Appends samples, grouped into one write per (day, player) file. Returns the days touched. */
    public Set<LocalDate> append(UUID uuid, List<Sample> samples) {
        Map<LocalDate, StringBuilder> byDay = new TreeMap<>();
        for (Sample s : samples) {
            byDay.computeIfAbsent(dayOf(s.time()), d -> new StringBuilder())
                    .append(s.serialize()).append('\n');
        }
        for (Map.Entry<LocalDate, StringBuilder> e : byDay.entrySet()) {
            Path file = root.resolve(e.getKey().toString()).resolve(uuid + ".tsv");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, e.getValue(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return byDay.keySet();
    }

    public Map<UUID, List<Sample>> read(LocalDate day) {
        Map<UUID, List<Sample>> out = new HashMap<>();
        Path dir = root.resolve(day.toString());
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String fn = f.getFileName().toString();
                if (!fn.endsWith(".tsv")) continue;
                UUID uuid;
                try {
                    uuid = UUID.fromString(fn.substring(0, fn.length() - 4));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                List<Sample> list = new ArrayList<>();
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    Sample s = Sample.parse(line);
                    if (s != null) list.add(s);
                }
                if (!list.isEmpty()) out.put(uuid, list);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Every day that has a history folder, oldest first. */
    public List<LocalDate> days() {
        List<LocalDate> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                try {
                    out.add(LocalDate.parse(d.getFileName().toString()));
                } catch (Exception ignored) {
                    // not a day folder
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Collections.sort(out);
        return out;
    }

    /** Deletes day folders older than {@code keepDays}. 0 keeps everything. Returns how many went. */
    public int prune(LocalDate today, int keepDays) {
        if (keepDays <= 0) return 0;
        LocalDate cutoff = today.minusDays(keepDays);
        int n = 0;
        for (LocalDate d : days()) {
            if (!d.isBefore(cutoff)) continue;
            deleteTree(root.resolve(d.toString()));
            n++;
        }
        return n;
    }

    public void rememberName(UUID uuid, String name) {
        if (!name.equals(names.put(uuid, name))) namesDirty = true;
    }

    public String nameOf(UUID uuid) {
        return names.getOrDefault(uuid, uuid.toString().substring(0, 8));
    }

    public void saveNames() {
        if (!namesDirty) return;
        StringBuilder b = new StringBuilder();
        new TreeMap<>(names).forEach((u, n) -> b.append(u).append('\t').append(n).append('\n'));
        try {
            Files.createDirectories(root);
            Path tmp = namesFile.resolveSibling("names.tsv.tmp");
            Files.writeString(tmp, b, StandardCharsets.UTF_8);
            Files.move(tmp, namesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            namesDirty = false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void loadNames() {
        if (!Files.isRegularFile(namesFile)) return;
        try {
            for (String line : Files.readAllLines(namesFile, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length != 2) continue;
                try {
                    names.put(UUID.fromString(p[0]), p[1]);
                } catch (IllegalArgumentException ignored) {
                    // skip a damaged line
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
