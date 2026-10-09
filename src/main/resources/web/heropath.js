/*
 * HeroPath: the web-map half.
 *
 * Adds a footprints button to BlueMap's control bar. It opens a panel where you pick a range
 * (one day, the last 7 days, the last 30 days) and the players. By default every route is
 * drawn complete, older parts fainter, like the Hero's Path in Zelda: Breath of the Wild.
 * The time bar replays them: it skips the hours when nobody was playing, so a week of short
 * sessions plays as one continuous story.
 *
 * On the routes: a dot where each player is at that moment, a cross where they died (with
 * the game's own death message), a star for each advancement, a ring where they arrived from
 * another dimension. The panel lists those "moments"; clicking one jumps there.
 *
 * Data comes from files the plugin writes next to this script:
 *   assets/heropath/index.json               days and who played
 *   assets/heropath/days/<date>/<map>.json   one day of one map (times relative to t0)
 *
 * Drawing uses BlueMap's own LineMarker and HtmlMarker, so lines look like every other line
 * on the map. They live in a THREE.Group of our own inside the marker scene: BlueMap
 * reconciles its MarkerSets against markers.json, and anything put in one would be wiped on
 * the next refresh.
 */
(function () {
    "use strict";

    const app = window.bluemap;
    const BM = window.BlueMap;
    if (!app || !BM) return;
    const THREE = BM.Three;
    const BASE = "assets/heropath/";
    // Sessions closer than this are one stretch on the time bar; a longer pause is skipped.
    const GAP_MERGE = 300;
    const SPEEDS = [1, 5, 10, 30, 60, 300, 1200];

    const LANG = (navigator.language || "en").toLowerCase();
    const ES = LANG.startsWith("es");
    const T = ES ? {
        title: "Ruta del héroe", range: "Periodo", day: "Un día", week: "7 días", month: "30 días",
        players: "Jugadores", all: "Todos", none: "Ninguno", play: "Reproducir", pause: "Pausa",
        start: "Al principio", end: "Ruta completa", speed: "Velocidad",
        speedHint: "Segundos de juego por cada segundo real",
        deaths: "Muertes", advs: "Logros", dims: "Portales", follow: "Seguir al jugador",
        show: "Mostrar", moments: "Momentos", noMoments: "Sin muertes, logros ni portales en este periodo.",
        noData: "Todavía no hay rutas guardadas.", noMap: "Nadie jugó en este mapa en este periodo.",
        loading: "Cargando…", focus: "Ver solo a este jugador", unfocus: "Ver a todos",
        played: "jugado", walked: "recorrido", died: "murió", from: "llegó desde",
        overworld: "el mundo normal", nether: "el Nether", end_dim: "el End",
        task: "Logro", goal: "Objetivo", challenge: "Desafío", collapse: "Plegar", expand: "Desplegar",
        close: "Cerrar", offline: "desconectado",
    } : {
        title: "Hero Path", range: "Period", day: "One day", week: "7 days", month: "30 days",
        players: "Players", all: "All", none: "None", play: "Play", pause: "Pause",
        start: "To the start", end: "Full route", speed: "Speed",
        speedHint: "Game seconds per real second",
        deaths: "Deaths", advs: "Advancements", dims: "Portals", follow: "Follow player",
        show: "Show", moments: "Moments", noMoments: "No deaths, advancements or portals in this period.",
        noData: "No routes recorded yet.", noMap: "Nobody played on this map in this period.",
        loading: "Loading…", focus: "Show only this player", unfocus: "Show everyone",
        played: "played", walked: "walked", died: "died", from: "arrived from",
        overworld: "the Overworld", nether: "the Nether", end_dim: "the End",
        task: "Advancement", goal: "Goal", challenge: "Challenge", collapse: "Collapse", expand: "Expand",
        close: "Close", offline: "offline",
    };

    // ── preferences (per viewer, optional) ───────────────────
    function pref(key, fallback) {
        try { const v = localStorage.getItem("heropath." + key); return v === null ? fallback : JSON.parse(v); }
        catch (e) { return fallback; }
    }
    function savePref(key, value) {
        try { localStorage.setItem("heropath." + key, JSON.stringify(value)); } catch (e) { /* private mode */ }
    }

    // ── state ────────────────────────────────────────────────
    let index = null;                    // index.json
    let range = pref("range", "week");   // "day" | "week" | "month"
    let day = null;                      // the date when range == "day"
    let mapId = null;
    let players = [];                    // merged over the range, see loadRange()
    let selected = new Set();
    let focus = null;                    // uuid shown alone, or null
    let intervals = [];                  // [[realStart, realEnd, virtualStart]]
    let vMax = 0, v = 0;                 // virtual seconds on the bar
    let rStart = 0, rEnd = 0;            // first and last real second in the range
    let playing = false;
    let speed = pref("speed", 30);
    let show = pref("show", { D: true, A: true, W: true });
    let follow = false;
    let open = false;
    let collapsed = false;

    const group = new THREE.Group();
    group.name = "heropath";
    app.mapViewer.markers.add(group);
    const drawn = new Map();             // uuid -> {segs: [{m, state, key}], head, events: [m]}

    // ── time ─────────────────────────────────────────────────
    function tz() { return (index && index.tz) || undefined; }

    /** Epoch seconds of local midnight of `date` in the server's time zone (old files had no t0). */
    function tzMidnight(date) {
        const guess = Date.parse(date + "T00:00:00Z") / 1000;
        try {
            const parts = new Intl.DateTimeFormat("en-US", {
                timeZone: tz(), hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit",
                hour: "2-digit", minute: "2-digit", second: "2-digit",
            }).formatToParts(new Date(guess * 1000)).reduce((o, p) => (o[p.type] = p.value, o), {});
            const wall = Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour, +parts.minute, +parts.second) / 1000;
            return guess - (wall - guess);
        } catch (e) { return guess; }
    }

    function fmt(sec, withDay) {
        const opts = { timeZone: tz(), hour: "2-digit", minute: "2-digit" };
        if (withDay) Object.assign(opts, { weekday: "short", day: "numeric", month: "short" });
        try { return new Date(sec * 1000).toLocaleString(ES ? "es-ES" : LANG, opts); }
        catch (e) { return new Date(sec * 1000).toLocaleString(); }
    }

    function duration(sec) {
        const m = Math.round(sec / 60);
        if (m < 60) return m + " min";
        return Math.floor(m / 60) + " h " + String(m % 60).padStart(2, "0") + " min";
    }

    function distance(blocks) {
        if (blocks < 1000) return Math.round(blocks) + " m";
        return (blocks / 1000).toLocaleString(ES ? "es-ES" : LANG, { maximumFractionDigits: 1 }) + " km";
    }

    /** The time bar skips pauses: intervals of play, merged when closer than GAP_MERGE. */
    function buildTimeline() {
        const spans = [];
        for (const p of players) {
            if (!selected.has(p.uuid)) continue;
            for (const s of p.segs) spans.push([s.start, s.end]);
            for (const e of p.events) spans.push([e[0], e[0]]);
        }
        spans.sort((a, b) => a[0] - b[0]);
        intervals = [];
        let vAcc = 0;
        for (const [a, b] of spans) {
            const last = intervals[intervals.length - 1];
            if (last && a - last[1] <= GAP_MERGE) {
                if (b > last[1]) { vAcc += b - last[1]; last[1] = b; }
            } else {
                intervals.push([a, b, vAcc]);
                vAcc += Math.max(1, b - a);
            }
        }
        vMax = vAcc;
        rStart = intervals.length ? intervals[0][0] : 0;
        rEnd = intervals.length ? intervals[intervals.length - 1][1] : 0;
    }

    function realOf(vv) {
        for (let i = intervals.length - 1; i >= 0; i--) {
            const [a, b, va] = intervals[i];
            if (vv >= va) return Math.min(b, a + (vv - va));
        }
        return rStart;
    }

    function virtualOf(real) {
        let out = 0;
        for (const [a, b, va] of intervals) {
            if (real < a) break;
            out = va + Math.min(real, b) - a;
        }
        return out;
    }

    // ── data ─────────────────────────────────────────────────
    function getJson(path) {
        return fetch(BASE + path + "?t=" + Date.now(), { cache: "no-store" })
            .then(r => r.ok ? r.json() : Promise.reject(new Error(r.status + " " + path)));
    }

    function currentMapId() {
        try { return app.mapViewer.map.data.id; } catch (e) { return null; }
    }

    function loadIndex() {
        return getJson("index.json").then(j => { index = j; }).catch(() => { index = { days: [] }; });
    }

    function datesInRange() {
        const all = ((index && index.days) || []).map(d => d.date).sort().reverse();
        if (!all.length) return [];
        if (range === "day") return [day && all.includes(day) ? day : all[0]];
        const newest = Date.parse(all[0] + "T12:00:00Z");
        const days = range === "week" ? 7 : 30;
        return all.filter(d => newest - Date.parse(d + "T12:00:00Z") < days * 86400000);
    }

    function loadRange() {
        clearDrawing();
        players = [];
        mapId = currentMapId();
        const dates = datesInRange();
        if (!mapId || !dates.length) { buildBody(); return Promise.resolve(); }
        setStatus(T.loading);
        return Promise.all(dates.map(d => getJson("days/" + d + "/" + mapId + ".json").catch(() => null)))
            .then(docs => {
                const byUuid = new Map();
                docs.forEach((doc, i) => {
                    if (!doc) return;
                    const t0 = doc.t0 || tzMidnight(dates[i]);
                    for (const p of doc.players) {
                        let m = byUuid.get(p.uuid);
                        if (!m) {
                            m = { uuid: p.uuid, name: p.name, color: p.color, segs: [], events: [] };
                            byUuid.set(p.uuid, m);
                        }
                        m.name = p.name;
                        for (const seg of p.segments) {
                            const pts = seg.map(q => [t0 + q[0], q[1], q[2], q[3]]);
                            m.segs.push({ pts, start: pts[0][0], end: pts[pts.length - 1][0] });
                        }
                        for (const e of p.events) m.events.push([t0 + e[0], e[1], e[2], e[3], e[4], e[5] == null ? null : e[5]]);
                    }
                });
                players = [...byUuid.values()];
                for (const p of players) {
                    p.segs.sort((a, b) => a.start - b.start);
                    p.events.sort((a, b) => a[0] - b[0]);
                    p.stats = stats(p);
                }
                players.sort((a, b) => b.stats.played - a.stats.played);
                const keep = new Set(players.map(p => p.uuid));
                selected = selected.size ? new Set([...selected].filter(u => keep.has(u))) : new Set(keep);
                if (!selected.size) selected = new Set(keep);
                if (focus && !keep.has(focus)) focus = null;
                buildTimeline();
                v = vMax;               // complete routes first; the bar replays them
                buildBody();
                redraw();
            });
    }

    function stats(p) {
        let played = 0, walked = 0;
        for (const s of p.segs) {
            played += s.end - s.start;
            for (let i = 1; i < s.pts.length; i++) {
                const a = s.pts[i - 1], b = s.pts[i];
                walked += Math.hypot(b[1] - a[1], b[3] - a[3]);
            }
        }
        const count = k => p.events.filter(e => e[1] === k).length;
        return { played, walked, deaths: count("D"), advs: count("A") };
    }

    // ── drawing ──────────────────────────────────────────────
    function clearDrawing() {
        for (const d of drawn.values()) {
            d.segs.forEach(s => group.remove(s.m));
            group.remove(d.head);
            d.events.forEach(m => group.remove(m));
        }
        drawn.clear();
    }

    function rgba(hex, a) {
        const n = parseInt(hex.slice(1), 16);
        return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a };
    }

    function lerp(a, b, at) {
        const f = (at - a[0]) / Math.max(1, b[0] - a[0]);
        return [at, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f];
    }

    /** Where the player is at `at`: inside a segment, interpolated; between sessions, the last place seen. */
    function positionAt(p, at) {
        let last = null;
        for (const s of p.segs) {
            if (s.start > at) break;
            if (s.end <= at) { last = { pos: s.pts[s.pts.length - 1], online: s.end === at }; continue; }
            for (let i = 1; i < s.pts.length; i++) {
                if (s.pts[i][0] >= at) return { pos: lerp(s.pts[i - 1], s.pts[i], at), online: true };
            }
        }
        return last;
    }

    function fade(seg) {
        // Older routes are fainter, newest at full strength: the Zelda look over a week.
        if (rEnd - rStart < 3600) return 1;
        return 0.45 + 0.55 * Math.max(0, Math.min(1, (seg.end - rStart) / (rEnd - rStart)));
    }

    function iconHtml(p, e) {
        const who = esc(p.name), when = fmt(e[0], range !== "day");
        if (e[1] === "D") {
            const why = e[5] ? esc(e[5]) : who + " " + T.died;
            return `<div class="hp-ev hp-death" style="--hp:${p.color}" title="${why} · ${when}">✕</div>`;
        }
        if (e[1] === "A") {
            const [frame, name] = splitAdv(e[5]);
            return `<div class="hp-ev hp-adv hp-${frame}" title="${who}: ${esc(name)} · ${when}">★</div>`;
        }
        return `<div class="hp-ev hp-dim" title="${who} ${T.from} ${esc(dimName(e[5]))} · ${when}">◎</div>`;
    }

    function splitAdv(detail) {
        const s = String(detail || "task|?");
        const i = s.indexOf("|");
        const frame = i > 0 ? s.slice(0, i) : "task";
        return [["task", "goal", "challenge"].includes(frame) ? frame : "task", i > 0 ? s.slice(i + 1) : s];
    }

    function dimName(d) {
        return { overworld: T.overworld, nether: T.nether, end: T.end_dim }[d] || d || "?";
    }

    function redraw() {
        const t = realOf(v);
        const ended = v >= vMax;
        for (const p of players) {
            const visible = open && selected.has(p.uuid) && (!focus || focus === p.uuid);
            let d = drawn.get(p.uuid);
            if (!d) {
                if (!visible) continue;
                d = { segs: [], head: new BM.HtmlMarker("hp-head-" + p.uuid), events: [] };
                group.add(d.head);
                for (const e of p.events) {
                    if (!"DAW".includes(e[1])) continue;
                    const m = new BM.HtmlMarker("hp-ev-" + p.uuid + "-" + e[0] + e[1]);
                    m.updateFromData({
                        position: { x: e[2], y: e[3] + 1, z: e[4] }, anchor: { x: 10, y: 10 },
                        classes: [], html: iconHtml(p, e),
                    });
                    m.hpEvent = e;
                    d.events.push(m);
                    group.add(m);
                }
                drawn.set(p.uuid, d);
            }
            p.segs.forEach((s, i) => {
                let slot = d.segs[i];
                if (!visible || s.start > t) { if (slot) slot.m.visible = false; return; }
                if (!slot) { slot = { m: new BM.LineMarker("hp-line-" + p.uuid + "-" + i), key: "" }; group.add(slot.m); d.segs[i] = slot; }
                const full = s.end <= t;
                const alpha = fade(s);
                const key = full ? "full" + alpha : "part" + t;
                if (slot.key !== key) {
                    let pts = s.pts;
                    if (!full) {
                        const k = pts.findIndex(q => q[0] > t);
                        pts = pts.slice(0, k).concat([lerp(pts[k - 1], pts[k], t)]);
                    }
                    if (pts.length < 2) { slot.m.visible = false; return; }
                    const line = pts.map(q => ({ x: q[1], y: q[2] + 0.5, z: q[3] }));
                    slot.m.updateFromData({
                        position: line[0], line, label: p.name, depthTest: false,
                        lineWidth: full ? 4 : 6, lineColor: rgba(p.color, full ? alpha : 1),
                    });
                    slot.key = key;
                }
                slot.m.visible = true;
            });
            const here = visible ? positionAt(p, t) : null;
            if (here) {
                const off = !here.online && !ended;
                d.head.updateFromData({
                    position: { x: here.pos[1], y: here.pos[2] + 1.5, z: here.pos[3] }, anchor: { x: 0, y: 0 },
                    classes: [], html: `<div class="hp-head${off ? " hp-off" : ""}" style="--hp:${p.color}"><span>${esc(p.name)}${off ? " · " + T.offline : ""}</span></div>`,
                });
                d.head.visible = true;
            } else {
                d.head.visible = false;
            }
            d.events.forEach(m => { m.visible = visible && show[m.hpEvent[1]] && m.hpEvent[0] <= t; });
        }
        if (follow) followPlayer(t);
        updateClock(t);
    }

    function moveCamera(x, z, extent) {
        const c = app.mapViewer.controlsManager;
        c.position.x = x;
        c.position.z = z;
        if (extent !== undefined && typeof c.distance === "number") c.distance = Math.max(80, Math.min(4000, extent * 1.3));
    }

    function followPlayer(t) {
        const p = players.find(q => selected.has(q.uuid) && (!focus || focus === q.uuid) && positionAt(q, t));
        if (!p) return;
        const here = positionAt(p, t);
        moveCamera(here.pos[1], here.pos[3]);
    }

    function flyToPlayer(p) {
        let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
        for (const s of p.segs) for (const q of s.pts) {
            minX = Math.min(minX, q[1]); maxX = Math.max(maxX, q[1]);
            minZ = Math.min(minZ, q[3]); maxZ = Math.max(maxZ, q[3]);
        }
        if (minX === Infinity) return;
        moveCamera((minX + maxX) / 2, (minZ + maxZ) / 2, Math.max(maxX - minX, maxZ - minZ));
    }

    // ── playback ─────────────────────────────────────────────
    let lastFrame = 0;
    function tick(now) {
        if (!playing) return;
        const dt = lastFrame ? (now - lastFrame) / 1000 : 0;
        lastFrame = now;
        setV(Math.min(vMax, v + dt * speed));
        if (v >= vMax) { setPlaying(false); return; }
        requestAnimationFrame(tick);
    }

    function setPlaying(on) {
        playing = on;
        if (playBtn) {
            playBtn.textContent = on ? "❚❚" : "▶";
            playBtn.title = on ? T.pause : T.play;
            playBtn.setAttribute("aria-label", playBtn.title);
        }
        if (on) {
            if (v >= vMax) setV(0);
            lastFrame = 0;
            requestAnimationFrame(tick);
        }
    }

    function setV(nv) {
        v = Math.max(0, Math.min(vMax, nv));
        if (slider) slider.value = String(v);
        redraw();
    }

    // ── UI ───────────────────────────────────────────────────
    let button = null, panel = null, body = null, slider = null, playBtn = null, clockEl = null, statusEl = null;

    function esc(s) {
        return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
    }

    function setStatus(text) {
        if (statusEl) { statusEl.textContent = text || ""; statusEl.hidden = !text; }
    }

    function updateClock(t) {
        if (clockEl) clockEl.textContent = intervals.length ? fmt(t, range !== "day") : "";
    }

    function createButton() {
        button = document.createElement("div");
        button.className = "svg-button hp-button";
        button.title = T.title;
        // footprints
        button.innerHTML = `<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <ellipse cx="8" cy="6.5" rx="2.6" ry="3.6"/><ellipse cx="7.2" cy="13.2" rx="1.9" ry="1.5"/>
            <ellipse cx="16" cy="10.5" rx="2.6" ry="3.6"/><ellipse cx="15.2" cy="17.2" rx="1.9" ry="1.5"/></svg>`;
        button.addEventListener("click", toggle);
        const bar = document.querySelector(".control-bar");
        if (!bar) return false;
        const ref = [...bar.children].find(el => el.className === "space thin-hide greedy");
        if (ref) bar.insertBefore(button, ref); else bar.appendChild(button);
        return true;
    }

    function createPanel() {
        panel = document.createElement("section");
        panel.className = "hp-panel";
        panel.hidden = true;
        panel.setAttribute("aria-label", T.title);
        panel.innerHTML = `
            <header>
              <strong>${T.title}</strong>
              <span class="hp-actions">
                <button type="button" class="hp-collapse" aria-expanded="true" title="${T.collapse}">–</button>
                <button type="button" class="hp-close" title="${T.close}" aria-label="${T.close}">×</button>
              </span>
            </header>
            <div class="hp-main">
              <div class="hp-seg" role="group" aria-label="${T.range}">
                <button type="button" data-range="day">${T.day}</button>
                <button type="button" data-range="week">${T.week}</button>
                <button type="button" data-range="month">${T.month}</button>
              </div>
              <select id="hp-day" aria-label="${T.day}"></select>
              <p class="hp-status" hidden></p>
              <div class="hp-body"></div>
            </div>`;
        document.body.appendChild(panel);
        panel.querySelector(".hp-close").addEventListener("click", toggle);
        const col = panel.querySelector(".hp-collapse");
        col.addEventListener("click", () => {
            collapsed = !collapsed;
            panel.classList.toggle("hp-collapsed", collapsed);
            col.textContent = collapsed ? "+" : "–";
            col.title = collapsed ? T.expand : T.collapse;
            col.setAttribute("aria-expanded", String(!collapsed));
        });
        statusEl = panel.querySelector(".hp-status");
        body = panel.querySelector(".hp-body");
        panel.querySelectorAll("[data-range]").forEach(b => b.addEventListener("click", () => {
            range = b.dataset.range;
            savePref("range", range);
            setPlaying(false);
            syncRangeButtons();
            loadRange();
        }));
        panel.querySelector("#hp-day").addEventListener("change", e => { day = e.target.value; setPlaying(false); loadRange(); });
    }

    function syncRangeButtons() {
        panel.querySelectorAll("[data-range]").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.range === range)));
        panel.querySelector("#hp-day").hidden = range !== "day";
    }

    function fillDays() {
        const sel = panel.querySelector("#hp-day");
        sel.innerHTML = "";
        const days = (index && index.days) || [];
        for (const d of days) {
            const o = document.createElement("option");
            o.value = d.date;
            o.textContent = new Date(d.date + "T12:00:00").toLocaleDateString(ES ? "es-ES" : LANG,
                { weekday: "short", day: "numeric", month: "short" }) + " · " + d.players.length;
            sel.appendChild(o);
        }
        if (days.length && (!day || !days.some(d => d.date === day))) day = days[0].date;
        if (day) sel.value = day;
        syncRangeButtons();
    }

    function buildBody() {
        body.innerHTML = "";
        slider = playBtn = clockEl = null;
        if (!index || !index.days.length) { setStatus(T.noData); return; }
        if (!players.length) { setStatus(T.noMap); return; }
        setStatus("");

        // players
        const list = document.createElement("div");
        list.className = "hp-players";
        list.innerHTML = `<div class="hp-row hp-small"><span>${T.players}</span>
            <button type="button" data-all="1">${T.all}</button><button type="button" data-all="0">${T.none}</button></div>`;
        for (const p of players) {
            const row = document.createElement("div");
            row.className = "hp-player" + (focus === p.uuid ? " hp-focused" : "") + (focus && focus !== p.uuid ? " hp-dimmed" : "");
            const st = p.stats;
            row.innerHTML = `
                <input type="checkbox" ${selected.has(p.uuid) ? "checked" : ""} aria-label="${esc(p.name)}">
                <button type="button" class="hp-name" title="${focus === p.uuid ? T.unfocus : T.focus}">
                  <i style="background:${p.color}"></i><span>${esc(p.name)}</span></button>
                <small>${duration(st.played)} ${T.played} · ${distance(st.walked)} ${T.walked}${st.deaths ? " · ✕ " + st.deaths : ""}${st.advs ? " · ★ " + st.advs : ""}</small>`;
            row.querySelector("input").addEventListener("change", e => {
                if (e.target.checked) selected.add(p.uuid); else selected.delete(p.uuid);
                const real = realOf(v), atEnd = v >= vMax;
                buildTimeline();
                v = atEnd ? vMax : virtualOf(real);
                if (slider) { slider.max = String(vMax); slider.value = String(v); }
                redraw();
            });
            row.querySelector(".hp-name").addEventListener("click", () => {
                focus = focus === p.uuid ? null : p.uuid;
                if (focus) { selected.add(p.uuid); flyToPlayer(p); }
                buildBody();
                redraw();
            });
            list.appendChild(row);
        }
        list.querySelectorAll("button[data-all]").forEach(b => b.addEventListener("click", () => {
            selected = b.dataset.all === "1" ? new Set(players.map(p => p.uuid)) : new Set();
            focus = null;
            buildTimeline();
            v = vMax;
            buildBody();
            redraw();
        }));
        body.appendChild(list);

        // time
        const time = document.createElement("div");
        time.className = "hp-time";
        time.innerHTML = `
            <div class="hp-row hp-transport">
              <button type="button" class="hp-first" title="${T.start}" aria-label="${T.start}">⏮</button>
              <button type="button" class="hp-play"></button>
              <button type="button" class="hp-last" title="${T.end}">${T.end}</button>
              <output class="hp-clock"></output>
            </div>
            <input type="range" class="hp-slider" min="0" max="${vMax}" step="1" value="${v}" aria-label="time">
            <div class="hp-row hp-small">
              <label title="${T.speedHint}">${T.speed} <select class="hp-speed">
                ${SPEEDS.map(s => `<option value="${s}">×${s}</option>`).join("")}</select></label>
              <label><input type="checkbox" class="hp-follow" ${follow ? "checked" : ""}> ${T.follow}</label>
            </div>
            <div class="hp-row hp-small hp-show"><span>${T.show}</span>
              <label><input type="checkbox" data-show="D" ${show.D ? "checked" : ""}> <b class="hp-key hp-death">✕</b> ${T.deaths}</label>
              <label><input type="checkbox" data-show="A" ${show.A ? "checked" : ""}> <b class="hp-key hp-adv hp-goal">★</b> ${T.advs}</label>
              <label><input type="checkbox" data-show="W" ${show.W ? "checked" : ""}> <b class="hp-key hp-dim">◎</b> ${T.dims}</label>
            </div>`;
        body.appendChild(time);
        slider = time.querySelector(".hp-slider");
        playBtn = time.querySelector(".hp-play");
        clockEl = time.querySelector(".hp-clock");
        slider.addEventListener("input", () => { setPlaying(false); setV(Number(slider.value)); });
        playBtn.addEventListener("click", () => setPlaying(!playing));
        time.querySelector(".hp-first").addEventListener("click", () => { setPlaying(false); setV(0); });
        time.querySelector(".hp-last").addEventListener("click", () => { setPlaying(false); setV(vMax); });
        const sp = time.querySelector(".hp-speed");
        sp.value = String(SPEEDS.includes(speed) ? speed : 30);
        sp.addEventListener("change", () => { speed = Number(sp.value); savePref("speed", speed); });
        time.querySelector(".hp-follow").addEventListener("change", e => { follow = e.target.checked; redraw(); });
        time.querySelectorAll("[data-show]").forEach(c => c.addEventListener("change", () => {
            show[c.dataset.show] = c.checked;
            savePref("show", show);
            buildMoments();
            redraw();
        }));

        // moments
        const moments = document.createElement("details");
        moments.className = "hp-moments";
        moments.open = pref("momentsOpen", true);
        moments.addEventListener("toggle", () => savePref("momentsOpen", moments.open));
        moments.innerHTML = `<summary>${T.moments}</summary><ol></ol>`;
        body.appendChild(moments);
        buildMoments();
        setPlaying(false);
    }

    function buildMoments() {
        const ol = body.querySelector(".hp-moments ol");
        if (!ol) return;
        ol.innerHTML = "";
        const items = [];
        for (const p of players) {
            if (!selected.has(p.uuid) || (focus && focus !== p.uuid)) continue;
            for (const e of p.events) if (show[e[1]]) items.push([p, e]);
        }
        items.sort((a, b) => b[1][0] - a[1][0]);
        const summary = body.querySelector(".hp-moments summary");
        if (summary) summary.textContent = T.moments + " · " + items.length;
        if (!items.length) { ol.innerHTML = `<li class="hp-small">${T.noMoments}</li>`; return; }
        for (const [p, e] of items.slice(0, 150)) {
            const li = document.createElement("li");
            let text;
            if (e[1] === "D") text = e[5] || p.name + " " + T.died;
            else if (e[1] === "A") { const [frame, name] = splitAdv(e[5]); text = `${p.name}: ${T[frame]} «${name}»`; }
            else text = `${p.name} ${T.from} ${dimName(e[5])}`;
            const cls = e[1] === "D" ? "hp-death" : e[1] === "A" ? "hp-adv hp-" + splitAdv(e[5])[0] : "hp-dim";
            const icon = e[1] === "D" ? "✕" : e[1] === "A" ? "★" : "◎";
            li.innerHTML = `<button type="button"><b class="hp-key ${cls}" style="--hp:${p.color}">${icon}</b>
                <span>${esc(text)}</span><small>${esc(fmt(e[0], range !== "day"))}</small></button>`;
            li.querySelector("button").addEventListener("click", () => {
                setPlaying(false);
                setV(virtualOf(e[0]));
                moveCamera(e[2], e[4], 120);
            });
            ol.appendChild(li);
        }
    }

    function toggle() {
        open = !open;
        button.classList.toggle("active", open);
        panel.hidden = !open;
        if (open) {
            loadIndex().then(() => { fillDays(); return loadRange(); });
        } else {
            setPlaying(false);
            redraw();
        }
    }

    function watchMap() {
        setInterval(() => {
            const id = currentMapId();
            if (open && id && id !== mapId) loadRange();
        }, 700);
    }

    function start() {
        if (!createButton()) { setTimeout(start, 500); return; }
        createPanel();
        watchMap();
        window.heropath = {
            version: 2,
            state: () => ({ range, day, mapId, open, players: players.length, drawn: drawn.size, v, vMax,
                            t: realOf(v), intervals: intervals.length, selected: selected.size, focus }),
            setV,
        };
    }

    start();
})();
