package com.multitec.heropath;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.LineMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Line;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * HeroPath: where every player went, on the BlueMap web map.
 *
 * <p>A fork of BMTrails (Mark-225, MIT). BMTrails draws the last minute of each player's
 * movement as a BlueMap line marker and forgets it. HeroPath keeps that live trail and adds
 * what the Zelda "Hero's Path" shows: the whole day, kept on disk, replayable on the web map
 * with a time slider.
 *
 * <p>Threads: the sampling task runs on the server thread and only reads player locations.
 * Everything else (history files, the live markers, the web export) happens on one IO
 * thread, so the game never waits for a disk.
 */
public final class HeroPath extends JavaPlugin implements Listener {

    private static final String PERM_UNTRACKED = "heropath.untracked";
    private static final String PERM_ADMIN = "heropath.admin";
    private static final String ASSET_DIR = "assets/heropath";

    // config
    private int samplingTicks;
    private double minMove;
    private double jumpDistance;
    private long keepAliveMillis;
    private int historyKeepDays;
    private int webDays;
    private long exportMillis;
    private boolean liveEnabled;
    private int livePoints;
    private int liveWidth;
    private String liveLabel;
    private boolean liveVisible;
    private ZoneId zone;

    private ExecutorService io;
    private HistoryStore store;
    private volatile WebExporter exporter;
    private volatile BlueMapAPI blueMap;
    private volatile Map<String, List<String>> mapsByWorld = Map.of();
    private final Map<UUID, MarkerSet> liveSets = new ConcurrentHashMap<>();

    // owned by the IO thread
    private final Map<UUID, Recorder> recorders = new HashMap<>();
    private final Map<UUID, List<Sample>> pending = new HashMap<>();
    private final Map<UUID, Deque<Sample>> live = new HashMap<>();
    private final Set<LocalDate> dirtyDays = new TreeSet<>();
    private long lastExport;
    private long lastLive;

    private BukkitTask samplingTask;
    private volatile String lastExportResult = "not yet";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        store = new HistoryStore(getDataFolder().toPath().resolve("history"), zone);
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HeroPath-IO");
            t.setDaemon(true);
            return t;
        });
        getServer().getPluginManager().registerEvents(this, this);
        // Players already online (a plugin reload) start a segment as if they had just joined.
        for (Player p : Bukkit.getOnlinePlayers()) startSegment(p);
        samplingTask = Bukkit.getScheduler().runTaskTimer(this, this::sample, samplingTicks, samplingTicks);

        BlueMapAPI.onEnable(this::onBlueMapEnable);
        BlueMapAPI.onDisable(api -> {
            blueMap = null;
            exporter = null;
            liveSets.clear();
        });
        getLogger().info("Recording every " + samplingTicks + " ticks into " + store.zone()
                + " days; history kept " + (historyKeepDays <= 0 ? "forever" : historyKeepDays + " days")
                + ", last " + webDays + " days on the web map");
    }

    @Override
    public void onDisable() {
        if (samplingTask != null) samplingTask.cancel();
        if (io != null) {
            // A stop is a quit for everyone still online, so their line ends where they stood.
            long now = System.currentTimeMillis();
            Map<UUID, Sample> quits = new LinkedHashMap<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (tracked(p, blueMap)) quits.put(p.getUniqueId(), event(p, Sample.Kind.QUIT, p.getLocation(), now));
            }
            io.execute(() -> {
                quits.forEach((uuid, s) -> record(uuid, s, false));
                flush();
                exportNow(false);
            });
            io.shutdown();
            try {
                if (!io.awaitTermination(20, TimeUnit.SECONDS)) getLogger().warning("IO thread did not finish in 20 s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void readConfig() {
        var c = getConfig();
        samplingTicks = Math.max(1, c.getInt("history.samplingTicks", 100));
        minMove = c.getDouble("history.minMoveBlocks", 2.0);
        jumpDistance = c.getDouble("history.jumpDistance", 64.0);
        keepAliveMillis = c.getLong("history.keepAliveSeconds", 60) * 1000L;
        historyKeepDays = c.getInt("history.keepDays", 0);
        webDays = c.getInt("web.days", 30);
        exportMillis = Math.max(30, c.getLong("web.exportSeconds", 300)) * 1000L;
        liveEnabled = c.getBoolean("live.enabled", true);
        livePoints = Math.max(2, c.getInt("live.points", 36));
        liveWidth = Math.max(1, c.getInt("live.lineWidth", 3));
        liveLabel = c.getString("live.markerSetName", "Live trails");
        liveVisible = c.getBoolean("live.visibleByDefault", true);
        String tz = c.getString("timezone", "Europe/Madrid");
        try {
            zone = ZoneId.of(tz);
        } catch (Exception e) {
            getLogger().warning("Unknown timezone '" + tz + "', using UTC");
            zone = ZoneId.of("UTC");
        }
    }

    // ---------------------------------------------------------------- BlueMap

    private void onBlueMapEnable(BlueMapAPI api) {
        // Runs on whatever thread BlueMap enables on; everything Bukkit is read here once.
        Map<String, List<String>> byWorld = new HashMap<>();
        for (World w : Bukkit.getWorlds()) {
            api.getWorld(w).ifPresent(bw -> byWorld.put(w.getName(),
                    bw.getMaps().stream().map(BlueMapMap::getId).sorted().toList()));
        }
        mapsByWorld = Map.copyOf(byWorld);
        Path webRoot = api.getWebApp().getWebRoot();
        installWebAssets(api, webRoot);
        if (liveEnabled) createLiveSets(api);
        blueMap = api;
        exporter = new WebExporter(store, webRoot.resolve(ASSET_DIR), webDays,
                world -> mapsByWorld.getOrDefault(world, List.of()));
        io.execute(() -> exportNow(true));
        getLogger().info("BlueMap ready: " + byWorld);
    }

    private void installWebAssets(BlueMapAPI api, Path webRoot) {
        for (String f : List.of("heropath.js", "heropath.css")) {
            Path target = webRoot.resolve(ASSET_DIR).resolve(f);
            try (InputStream in = getResource("web/" + f)) {
                if (in == null) throw new IOException("missing resource web/" + f);
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not install " + f + " into the web map", e);
                return;
            }
        }
        api.getWebApp().registerScript(ASSET_DIR + "/heropath.js");
        api.getWebApp().registerStyle(ASSET_DIR + "/heropath.css");
    }

    private void createLiveSets(BlueMapAPI api) {
        liveSets.clear();
        for (World w : Bukkit.getWorlds()) {
            BlueMapWorld bw = api.getWorld(w).orElse(null);
            if (bw == null || bw.getMaps().isEmpty()) continue;
            MarkerSet set = MarkerSet.builder().label(liveLabel).defaultHidden(!liveVisible).toggleable(true).build();
            liveSets.put(w.getUID(), set);
            for (BlueMapMap m : bw.getMaps()) m.getMarkerSets().put("heropath_live", set);
        }
    }

    // ---------------------------------------------------------------- sampling (server thread)

    private record Snapshot(UUID uuid, String name, UUID worldId, Sample sample) {
    }

    private void sample() {
        BlueMapAPI api = blueMap;
        long now = System.currentTimeMillis();
        List<Snapshot> snaps = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!tracked(p, api)) continue;
            Location l = p.getLocation();
            snaps.add(new Snapshot(p.getUniqueId(), p.getName(), l.getWorld().getUID(),
                    new Sample(now, Sample.Kind.POINT, l.getWorld().getName(), l.getX(), l.getY(), l.getZ())));
        }
        io.execute(() -> {
            for (Snapshot s : snaps) {
                store.rememberName(s.uuid(), s.name());
                record(s.uuid(), s.sample(), true);
                liveWorld.put(s.uuid(), s.worldId());
            }
            live.keySet().retainAll(snaps.stream().map(Snapshot::uuid).toList());
            flush();
            long t = System.currentTimeMillis();
            if (liveEnabled && t - lastLive >= 10_000) {
                lastLive = t;
                updateLiveMarkers();
            }
            if (t - lastExport >= exportMillis) exportNow(false);
        });
    }

    private boolean tracked(Player p, BlueMapAPI api) {
        if (p.hasPermission(PERM_UNTRACKED)) return false;
        // Respect BlueMap's own "hide me from the map": a player hidden there is not drawn here either.
        return api == null || api.getWebApp().getPlayerVisibility(p.getUniqueId());
    }

    private Sample event(Player p, Sample.Kind kind, Location l, long now) {
        return new Sample(now, kind, l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }

    // ---------------------------------------------------------------- events (server thread)

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        startSegment(e.getPlayer());
    }

    private void startSegment(Player p) {
        if (!tracked(p, blueMap)) return;
        UUID uuid = p.getUniqueId();
        String name = p.getName();
        Sample s = event(p, Sample.Kind.JOIN, p.getLocation(), System.currentTimeMillis());
        io.execute(() -> {
            store.rememberName(uuid, name);
            record(uuid, s, false);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!tracked(p, blueMap)) return;
        UUID uuid = p.getUniqueId();
        Sample s = event(p, Sample.Kind.QUIT, p.getLocation(), System.currentTimeMillis());
        io.execute(() -> {
            record(uuid, s, false);
            recorders.remove(uuid);
            live.remove(uuid);
            flush();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!tracked(p, blueMap)) return;
        UUID uuid = p.getUniqueId();
        Sample s = event(p, Sample.Kind.DEATH, p.getLocation(), System.currentTimeMillis());
        io.execute(() -> record(uuid, s, false));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        breakTrail(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        breakTrail(e.getPlayer().getUniqueId());
    }

    /** The next position starts a new line: the player did not walk from where they were. */
    private void breakTrail(UUID uuid) {
        io.execute(() -> {
            recorders.remove(uuid);
            live.remove(uuid);
        });
    }

    // ---------------------------------------------------------------- IO thread

    private final Map<UUID, UUID> liveWorld = new HashMap<>();

    private void record(UUID uuid, Sample s, boolean addToLive) {
        Recorder r = recorders.computeIfAbsent(uuid, u -> new Recorder(minMove, jumpDistance, keepAliveMillis));
        Sample kept = r.offer(s);
        if (kept != null) pending.computeIfAbsent(uuid, u -> new ArrayList<>()).add(kept);
        if (addToLive && liveEnabled) {
            Deque<Sample> d = live.computeIfAbsent(uuid, u -> new ArrayDeque<>());
            if (kept != null && kept.kind() == Sample.Kind.JUMP) d.clear();
            d.addLast(s);
            while (d.size() > livePoints) d.removeFirst();
        }
    }

    private void flush() {
        if (pending.isEmpty()) return;
        try {
            for (Map.Entry<UUID, List<Sample>> e : pending.entrySet()) {
                dirtyDays.addAll(store.append(e.getKey(), e.getValue()));
            }
            store.saveNames();
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Could not write history", ex);
        }
        pending.clear();
    }

    private void exportNow(boolean announce) {
        lastExport = System.currentTimeMillis();
        WebExporter ex = exporter;
        if (ex == null) return;
        try {
            LocalDate today = store.dayOf(lastExport);
            int pruned = store.prune(today, historyKeepDays);
            int n = ex.export(dirtyDays, today);
            dirtyDays.clear();
            lastExportResult = n + " day file(s) at " + new Date(lastExport) + (pruned > 0 ? ", pruned " + pruned + " day(s)" : "");
            if (announce) getLogger().info("Web export: " + lastExportResult);
        } catch (RuntimeException e) {
            lastExportResult = "FAILED: " + e;
            getLogger().log(Level.SEVERE, "Web export failed", e);
        }
    }

    private void updateLiveMarkers() {
        if (liveSets.isEmpty()) return;
        for (MarkerSet set : liveSets.values()) {
            set.getMarkers().keySet().removeIf(k -> {
                try {
                    UUID u = UUID.fromString(k);
                    return !live.containsKey(u) || live.get(u).size() < 2 || liveSets.get(liveWorld.get(u)) != set;
                } catch (IllegalArgumentException e) {
                    return true;
                }
            });
        }
        for (Map.Entry<UUID, Deque<Sample>> e : live.entrySet()) {
            if (e.getValue().size() < 2) continue;
            MarkerSet set = liveSets.get(liveWorld.get(e.getKey()));
            if (set == null) continue;
            Vector3d[] pts = e.getValue().stream().map(s -> Vector3d.from(s.x(), s.y(), s.z())).toArray(Vector3d[]::new);
            String name = store.nameOf(e.getKey());
            int rgb = Colors.forPlayer(e.getKey());
            set.put(e.getKey().toString(), LineMarker.builder()
                    .label(name)
                    .line(new Line(pts))
                    .lineWidth(liveWidth)
                    .lineColor(new Color(0xff000000 | rgb))
                    .centerPosition()
                    .maxDistance(100_000)
                    .build());
        }
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage("No permission.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "export" -> {
                io.execute(() -> exportNow(true));
                sender.sendMessage("HeroPath: export queued");
            }
            case "status" -> sender.sendMessage("HeroPath " + getPluginMeta().getVersion()
                    + " | bluemap=" + (blueMap != null) + " | maps=" + mapsByWorld
                    + " | days on disk=" + store.days().size()
                    + " | last export: " + lastExportResult);
            default -> sender.sendMessage("Usage: /heropath [status|export]");
        }
        return true;
    }
}
