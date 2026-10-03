/*
 * HeroPath: the web-map half.
 *
 * Adds a footprints button to BlueMap's control bar. It opens a panel where you pick a day
 * and the players, then drag (or play) a time slider: each player's route is drawn up to
 * that moment, with a dot where they were and a cross where they died, like the Hero's
 * Path in Zelda: Breath of the Wild.
 *
 * Data comes from files the plugin writes next to this script:
 *   assets/heropath/index.json               days and who played
 *   assets/heropath/days/<date>/<map>.json   one day of one map
 *
 * Drawing uses BlueMap's own LineMarker and HtmlMarker, so lines look like every other
 * line on the map. They live in a THREE.Group of our own inside the marker scene: BlueMap
 * reconciles its MarkerSets against markers.json, and anything put in one would be wiped
 * on the next refresh.
 */
(function () {
    "use strict";

    const app = window.bluemap;
    const BM = window.BlueMap;
    if (!app || !BM) return;
    const THREE = BM.Three;
    const BASE = "assets/heropath/";

    const ES = (navigator.language || "").toLowerCase().startsWith("es");
    const T = ES ? {
        title: "Ruta del héroe",
        day: "Día",
        players: "Jugadores",
        all: "Todos",
        none: "Ninguno",
        play: "Reproducir",
        pause: "Pausa",
        speed: "Velocidad",
        deaths: "Mostrar muertes",
        follow: "Seguir al jugador",
        noData: "Todavía no hay rutas guardadas.",
        noMap: "Nadie jugó en este mapa ese día.",
        loading: "Cargando…",
        died: "murió aquí",
        updated: "Actualizado",
    } : {
        title: "Hero Path",
        day: "Day",
        players: "Players",
        all: "All",
        none: "None",
        play: "Play",
        pause: "Pause",
        speed: "Speed",
        deaths: "Show deaths",
        follow: "Follow player",
        noData: "No routes recorded yet.",
        noMap: "Nobody played on this map that day.",
        loading: "Loading…",
        died: "died here",
        updated: "Updated",
    };

    // ── state ────────────────────────────────────────────────
    let index = null;          // index.json
    let day = null;            // "2026-10-03"
    let mapId = null;
    let doc = null;            // the loaded day/map document
    let selected = new Set();  // uuids
    let t = 0;                 // seconds since midnight shown on the slider
    let tMin = 0, tMax = 0;
    let playing = false;
    let speed = 300;           // game seconds per real second
    let showDeaths = true;
    let follow = false;
    let open = false;

    const group = new THREE.Group();
    group.name = "heropath";
    app.mapViewer.markers.add(group);
    const drawn = new Map();   // uuid -> {line, head, deaths: []}

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

    function loadDay() {
        clearDrawing();
        doc = null;
        mapId = currentMapId();
        if (!day || !mapId) { body.innerHTML = ""; return Promise.resolve(); }
        setStatus(T.loading);
        return getJson("days/" + day + "/" + mapId + ".json")
            .then(j => { doc = j; })
            .catch(() => { doc = { players: [] }; })
            .then(() => {
                selected = new Set(doc.players.map(p => p.uuid));
                tMin = Math.min(...doc.players.map(p => p.from), 86400);
                tMax = Math.max(...doc.players.map(p => p.to), 0);
                if (tMax < tMin) { tMin = 0; tMax = 0; }
                t = tMax;
                buildPanelBody();
                redraw();
            });
    }

    // ── drawing ──────────────────────────────────────────────
    function clearDrawing() {
        for (const d of drawn.values()) {
            group.remove(d.line); d.line.dispose && d.line.dispose();
            group.remove(d.head);
            d.deaths.forEach(m => group.remove(m));
            (d.extra || []).forEach(m => group.remove(m));
        }
        drawn.clear();
    }

    function rgb(hex) {
        const n = parseInt(hex.slice(1), 16);
        return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a: 1 };
    }

    /** Position at time `at`, interpolated inside the segment that contains it. */
    function positionAt(player, at) {
        let last = null;
        for (const seg of player.segments) {
            for (let i = 0; i < seg.length; i++) {
                const p = seg[i];
                if (p[0] > at) {
                    if (i > 0 && last === seg[i - 1]) {
                        const a = seg[i - 1], f = (at - a[0]) / Math.max(1, p[0] - a[0]);
                        return [a[1] + (p[1] - a[1]) * f, a[2] + (p[2] - a[2]) * f, a[3] + (p[3] - a[3]) * f];
                    }
                    return last ? [last[1], last[2], last[3]] : null;
                }
                last = p;
            }
        }
        return last ? [last[1], last[2], last[3]] : null;
    }

    /**
     * The route up to time `at`, as one polyline per segment. A segment never crosses a
     * jump (teleport, portal, death), so no line is drawn across ground nobody walked.
     */
    function pieces(player, at) {
        const out = [];
        for (const seg of player.segments) {
            if (seg[0][0] > at) break;
            const part = [];
            for (const p of seg) {
                if (p[0] > at) {
                    const pos = positionAt({ segments: [seg] }, at);
                    if (pos) part.push({ x: pos[0], y: pos[1] + 0.5, z: pos[2] });
                    break;
                }
                part.push({ x: p[1], y: p[2] + 0.5, z: p[3] });
            }
            if (part.length > 1) out.push(part);
        }
        return out;
    }

    function redraw() {
        if (!doc) return;
        for (const player of doc.players) {
            const on = open && selected.has(player.uuid) && player.from <= t;
            let d = drawn.get(player.uuid);
            if (!on) {
                if (d) {
                    d.line.visible = false; d.head.visible = false;
                    d.deaths.forEach(m => m.visible = false);
                    (d.extra || []).forEach(m => group.remove(m)); d.extra = [];
                }
                continue;
            }
            if (!d) {
                d = {
                    line: new BM.LineMarker("heropath-line-" + player.uuid),
                    head: new BM.HtmlMarker("heropath-head-" + player.uuid),
                    deaths: [],
                };
                group.add(d.line);
                group.add(d.head);
                for (const ev of player.events) {
                    if (ev[1] !== "D") continue;
                    const m = new BM.HtmlMarker("heropath-death-" + player.uuid + "-" + ev[0]);
                    m.updateFromData({
                        position: { x: ev[2], y: ev[3] + 1, z: ev[4] },
                        anchor: { x: 9, y: 9 },
                        classes: [],
                        html: `<div class="hp-death" style="--hp:${player.color}" title="${esc(player.name)} ${T.died} ${clock(ev[0])}">✕</div>`,
                    });
                    m.hpTime = ev[0];
                    d.deaths.push(m);
                    group.add(m);
                }
                drawn.set(player.uuid, d);
            }
            const parts = pieces(player, t);
            // A LineMarker is one polyline, so the first segment is `line` and the rest get
            // markers of their own; a day usually has only a handful of segments.
            d.extra = d.extra || [];
            d.extra.forEach(m => group.remove(m));
            d.extra = [];
            if (parts.length) {
                setLine(d.line, parts[0], player.color, player.name);
                d.line.visible = true;
                for (let i = 1; i < parts.length; i++) {
                    const m = new BM.LineMarker("heropath-line-" + player.uuid + "-" + i);
                    setLine(m, parts[i], player.color, player.name);
                    group.add(m);
                    d.extra.push(m);
                }
            } else {
                d.line.visible = false;
            }
            const pos = positionAt(player, t);
            if (pos) {
                d.head.updateFromData({
                    position: { x: pos[0], y: pos[1] + 1.5, z: pos[2] },
                    anchor: { x: 0, y: 0 },
                    classes: [],
                    html: `<div class="hp-head" style="--hp:${player.color}"><span>${esc(player.name)}</span></div>`,
                });
                d.head.visible = true;
            } else {
                d.head.visible = false;
            }
            d.deaths.forEach(m => m.visible = showDeaths && m.hpTime <= t);
        }
        if (follow) followFirst();
        updateClock();
    }

    function setLine(marker, pts, color, name) {
        marker.updateFromData({
            position: pts[0],
            line: pts,
            label: name,
            lineWidth: 3,
            lineColor: rgb(color),
            depthTest: false,
        });
    }

    function followFirst() {
        const p = doc && doc.players.find(p => selected.has(p.uuid) && p.from <= t);
        if (!p) return;
        const pos = positionAt(p, t);
        if (!pos) return;
        const c = app.mapViewer.controlsManager;
        c.position.x = pos[0];
        c.position.z = pos[2];
    }

    // ── playback ─────────────────────────────────────────────
    let lastFrame = 0;
    function tick(now) {
        if (!playing) return;
        const dt = lastFrame ? (now - lastFrame) / 1000 : 0;
        lastFrame = now;
        t = Math.min(tMax, t + dt * speed);
        if (slider) slider.value = String(t);
        redraw();
        if (t >= tMax) { setPlaying(false); return; }
        requestAnimationFrame(tick);
    }

    function setPlaying(on) {
        playing = on;
        if (playBtn) playBtn.textContent = on ? "❚❚ " + T.pause : "▶ " + T.play;
        if (on) {
            if (t >= tMax) t = tMin;
            lastFrame = 0;
            requestAnimationFrame(tick);
        }
    }

    // ── UI ───────────────────────────────────────────────────
    let button = null, panel = null, body = null, slider = null, playBtn = null, clockEl = null, statusEl = null;

    function esc(s) {
        return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
    }

    function clock(sec) {
        sec = Math.max(0, Math.round(sec));
        const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
        return String(h).padStart(2, "0") + ":" + String(m).padStart(2, "0");
    }

    function setStatus(text) {
        if (statusEl) { statusEl.textContent = text || ""; statusEl.hidden = !text; }
    }

    function updateClock() {
        if (clockEl) clockEl.textContent = clock(t);
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
            <header><strong>${T.title}</strong><button type="button" class="hp-close" aria-label="close">×</button></header>
            <label class="hp-row">${T.day} <select id="hp-day"></select></label>
            <p class="hp-status" hidden></p>
            <div class="hp-body"></div>`;
        document.body.appendChild(panel);
        panel.querySelector(".hp-close").addEventListener("click", toggle);
        statusEl = panel.querySelector(".hp-status");
        body = panel.querySelector(".hp-body");
        panel.querySelector("#hp-day").addEventListener("change", e => { day = e.target.value; setPlaying(false); loadDay(); });
    }

    function fillDays() {
        const sel = panel.querySelector("#hp-day");
        sel.innerHTML = "";
        const days = (index && index.days) || [];
        if (!days.length) { setStatus(T.noData); return; }
        setStatus("");
        for (const d of days) {
            const o = document.createElement("option");
            o.value = d.date;
            o.textContent = new Date(d.date + "T12:00:00").toLocaleDateString(ES ? "es-ES" : undefined,
                { weekday: "short", day: "numeric", month: "short" }) + " · " + d.players.length;
            sel.appendChild(o);
        }
        if (!day || !days.some(d => d.date === day)) day = days[0].date;
        sel.value = day;
    }

    function buildPanelBody() {
        body.innerHTML = "";
        if (!doc || !doc.players.length) { setStatus(T.noMap); return; }
        setStatus("");
        const list = document.createElement("div");
        list.className = "hp-players";
        list.innerHTML = `<div class="hp-row hp-small">${T.players}
            <button type="button" data-all="1">${T.all}</button><button type="button" data-all="0">${T.none}</button></div>`;
        for (const p of doc.players) {
            const row = document.createElement("label");
            row.className = "hp-player";
            row.innerHTML = `<input type="checkbox" ${selected.has(p.uuid) ? "checked" : ""}>
                <i style="background:${p.color}"></i><span>${esc(p.name)}</span><small>${clock(p.from)}–${clock(p.to)}</small>`;
            row.querySelector("input").addEventListener("change", e => {
                if (e.target.checked) selected.add(p.uuid); else selected.delete(p.uuid);
                redraw();
            });
            list.appendChild(row);
        }
        list.querySelectorAll("button[data-all]").forEach(b => b.addEventListener("click", () => {
            selected = b.dataset.all === "1" ? new Set(doc.players.map(p => p.uuid)) : new Set();
            buildPanelBody();
            redraw();
        }));
        body.appendChild(list);

        const time = document.createElement("div");
        time.className = "hp-time";
        time.innerHTML = `
            <div class="hp-row"><button type="button" class="hp-play"></button><output class="hp-clock"></output></div>
            <input type="range" class="hp-slider" min="${tMin}" max="${tMax}" step="1" value="${t}" aria-label="time">
            <div class="hp-row hp-small">
              <label>${T.speed} <select class="hp-speed">
                <option value="60">×60</option><option value="300">×300</option><option value="1200">×1200</option></select></label>
            </div>
            <label class="hp-row hp-small"><input type="checkbox" class="hp-deaths" ${showDeaths ? "checked" : ""}> ${T.deaths}</label>
            <label class="hp-row hp-small"><input type="checkbox" class="hp-follow" ${follow ? "checked" : ""}> ${T.follow}</label>`;
        body.appendChild(time);
        slider = time.querySelector(".hp-slider");
        playBtn = time.querySelector(".hp-play");
        clockEl = time.querySelector(".hp-clock");
        slider.addEventListener("input", () => { t = Number(slider.value); redraw(); });
        playBtn.addEventListener("click", () => setPlaying(!playing));
        const sp = time.querySelector(".hp-speed");
        sp.value = String(speed);
        sp.addEventListener("change", () => { speed = Number(sp.value); });
        time.querySelector(".hp-deaths").addEventListener("change", e => { showDeaths = e.target.checked; redraw(); });
        time.querySelector(".hp-follow").addEventListener("change", e => { follow = e.target.checked; redraw(); });
        setPlaying(false);
        updateClock();
    }

    function toggle() {
        open = !open;
        button.classList.toggle("active", open);
        panel.hidden = !open;
        if (open) {
            loadIndex().then(() => { fillDays(); return loadDay(); });
        } else {
            setPlaying(false);
            redraw();
        }
    }

    function watchMap() {
        setInterval(() => {
            const id = currentMapId();
            if (open && id && id !== mapId) loadDay();
        }, 700);
    }

    function start() {
        if (!createButton()) { setTimeout(start, 500); return; }
        createPanel();
        watchMap();
        window.heropath = { version: 1, state: () => ({ day, mapId, t, open, players: doc ? doc.players.length : 0, drawn: drawn.size }) };
    }

    start();
})();
