<p align="center">
  <img src="docs/img/hero-3d.png" alt="HeroPath on BlueMap: a player's route drawn in red over the 3D map, with the Hero Path panel open at the bottom left" width="900">
</p>

# HeroPath

**Where every player went, on the BlueMap web map.** A Paper plugin that records each
player's route and lets you replay any day with a time slider, like the Hero's Path in
*Zelda: Breath of the Wild*.

![Paper](https://img.shields.io/badge/Paper-26.2-blue)
![Java](https://img.shields.io/badge/Java-25-orange)
![BlueMap](https://img.shields.io/badge/BlueMap-5.23%20%C2%B7%20API%202.7-1e88e5)
![Fork of](https://img.shields.io/badge/fork%20of-BMTrails-555)
![License](https://img.shields.io/badge/license-MIT-green)

| Read | For |
|---|---|
| [Quick start](#quick-start) | Build, install, the two commands |
| [How it works](#how-it-works) | The pieces and the file format |
| [Configuration](#configuration) | Every setting in `config.yml` |
| [`src/main/resources/web/heropath.js`](src/main/resources/web/heropath.js) | The web panel |

## What it is

[BMTrails](https://github.com/Mark-225/BMTrails) (Mark-225) draws the last minute of each
player's movement as a line on BlueMap and then forgets it. HeroPath keeps that **live
trail** and adds the part BMTrails never had: **history**. Every few seconds it writes
down where each player is, files it per day, and publishes it next to BlueMap's own web
files. A **footprints button** on the map opens a panel: pick a day and the players, then
drag or play the time slider. Each route is drawn up to that moment, with a dot where the
player was and a ✕ where they died. Teleports, portals and deaths break the line, so it
never crosses ground nobody walked.

It was written for the Multitec server (Paper 26.2, BlueMap served as static files behind
a login), so it needs **no web server of its own**: everything is plain JSON in BlueMap's
webroot, and works wherever BlueMap's webapp is served from.

## Quick start

```bash
# build (needs Docker; Paper 26.2's API requires Java 25, so the build runs in Gradle 9 / JDK 25)
docker run --rm -u "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/gradle -v gradle-cache:/gradle \
  -v "$PWD":/w -w /w gradle:9.1.0-jdk25 gradle --no-daemon -q build
# -> build/libs/heropath-2.0.0.jar   (also runs the 17 unit tests)

cp build/libs/heropath-2.0.0.jar <server>/plugins/    # BlueMap must be installed; restart the server
```

```text
/heropath status    # BlueMap found? which maps? days on disk, last web export
/heropath export    # rewrite the web files now instead of waiting for the next 5-minute pass
```

## Gallery

| Half-way through the day | A death on the route |
|---|---|
| ![The panel in Spanish with the slider at 85%: the route is drawn only up to that time](docs/img/slider-midway.png) | ![Flat view: the route walks south, turns east and ends at a red death marker](docs/img/death-marker.png) |
| The slider is at 85 %; the line stops where the player was at 11:01. | The ✕ marks where the player died; the respawn is a jump, not a line. |

The panel speaks Spanish or English, following the browser. The screenshots come from a
throwaway test world, walked by a scripted Bedrock client.

## How it works

```mermaid
flowchart LR
  P[Players online] -->|every 100 ticks| S[Sampling<br/>server thread]
  E[Join / quit / death /<br/>teleport events] --> IO
  S --> IO[HeroPath-IO thread]
  IO --> H[(history/&lt;date&gt;/&lt;uuid&gt;.tsv)]
  IO --> L[Live trails<br/>BlueMap markers]
  H --> X[Web export<br/>every 5 min]
  X --> W[webroot/assets/heropath<br/>index.json · days/&lt;date&gt;/&lt;map&gt;.json]
  W --> B[heropath.js in BlueMap's webapp]
```

| Piece | What it does |
|---|---|
| `Recorder` | Keeps a position only if the player moved ≥ 2 blocks, or a minute passed. A move > 64 blocks, or into another world, is a **jump** |
| `HistoryStore` | Append-only text, one file per player per local day: `epochMillis kind world x y z` (`P` point, `J` join, `Q` quit, `D` death, `T` jump). A torn last line is skipped |
| `DayExport` | One JSON per BlueMap map per day; times are seconds since local midnight |
| `WebExporter` | Writes atomically (temp file + move), keeps only the last `web.days`, rebuilds after a restart |
| `heropath.js` | Draws with BlueMap's own `LineMarker` / `HtmlMarker`, in a group of its own so BlueMap's marker refresh never wipes it |

## Configuration

`plugins/HeroPath/config.yml`, all optional:

| Key | Default | Meaning |
|---|---|---|
| `timezone` | `Europe/Madrid` | Which local day a sample belongs to, and the clock the slider shows |
| `history.samplingTicks` | `100` | How often positions are read (20 ticks = 1 s) |
| `history.minMoveBlocks` | `2.0` | Standing still writes nothing… |
| `history.keepAliveSeconds` | `60` | …except once a minute |
| `history.jumpDistance` | `64.0` | Longer than this between two samples was not walked |
| `history.keepDays` | `0` | Delete history older than this; `0` keeps it forever |
| `web.days` | `30` | Days published to the map |
| `web.exportSeconds` | `300` | How often the web files are rewritten |
| `live.*` | on, 36 points | The BMTrails live trail: points, width, layer name, visible by default |

<details>
<summary>Privacy and who is recorded</summary>

- A player hidden from BlueMap (BlueMap's own visibility setting) is neither recorded nor
  drawn.
- Permission `heropath.untracked` excludes a player entirely.
- The history is kept on the server's disk; only the last `web.days` days are published,
  and only to wherever BlueMap's webapp is served. Put that behind a login if routes should
  not be public: a route shows where someone lives.

</details>

<details>
<summary>Known limits</summary>

- On the two days a year the clock changes, the slider shows elapsed time since midnight,
  so it reads one hour off after the change.
- Only positions taken while the plugin runs are recorded; there is no history from before
  it was installed.
- The live trail is a BlueMap marker, so it is only as fresh as BlueMap's marker write
  interval (`write-markers-interval`).

</details>

## Credits

Fork of [Mark-225/BMTrails](https://github.com/Mark-225/BMTrails) (MIT). The live trail is
BMTrails' idea and largely its logic; the history, export and web panel are new.
Maintained by [Multitec-UA](https://github.com/Multitec-UA). MIT licence, see
[LICENSE](LICENSE).
