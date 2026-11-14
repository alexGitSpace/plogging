# web

What people see: the map of litter spots, outings, stats, quests, the leaderboard and the live outing screen. It reads everything from the API ([contract](../contract/README.md)). Desktop first; the screens follow the Figma mockup (page "Sport style (EN)").

Plain HTML and ES modules, no build step. Leaflet draws the maps and Three.js the animated character; both load from a CDN.

## Run locally

```bash
cd web && python3 -m http.server 5173   # http://localhost:5173
```

| Setting | How | Default |
|---|---|---|
| API address | `?api=https://…` in the page address, remembered in the browser (`?api=default` forgets it); or `js/config.js` (copy `js/config.example.js`, not in git) | `http://localhost:8000` |
| You | `?user=<nickname>`, or the selector at the bottom of the sidebar | The leader of the leaderboard |

The API address stays out of git: the API has no authentication yet. Ask in the team chat. The shared API already holds simulated outings of 10 people; for a live outing, run `python3 api/sim.py live` ([api/README.md](../api/README.md#simulator)).

## Deploy

`Dockerfile` serves the folder with Caddy on `$PORT` (8080 by default). Set `API_URL` in the host's environment: the container writes it into `js/config.js` when it starts. On Railway: a service from this repository with Root Directory `/web` and Watch Paths `/web/**`, the variable `API_URL`, and a generated domain. Every push to `main` that touches `web/` redeploys it.

```bash
docker build -t plogging-web web && docker run -p 8080:8080 -e API_URL=https://… plogging-web
```

## Layout

| Path | What it is |
|---|---|
| `index.html` | The shell: sidebar, the view, and the import map for `three` and `three/addons/` |
| `css/app.css` | Colours and shapes from the mockup as variables on `:root`, plus shared classes: `card`, `big`, `eyebrow`, `pill`, `btn`, `avatar`, `grid-2`, `grid-3` |
| `js/main.js` | Sidebar and router on `#/feed`, `#/quests`, `#/map`, `#/leaders`, `#/profile`, `#/outing/<session_id>`, `#/done/<session_id>` |
| `js/api.js` | One function per API request, `poll()` for live screens, `weekStart()` for "this week", and the two settings |
| `js/map.js` | A Leaflet map in the light style of the mockup |
| `js/ui.js` | `esc()`, formatting (`km`, `duration`, `clock`, `ago`), litter names and icons |
| `js/views/` | One module per screen |
| `assets/` | Images and 3D models; keep each file under a few MB |

## Conventions

- **A screen** is a module in `js/views/` with `export default async function render(el, param)`. It fills `el`, and returns a function that cleans up: stops polling, removes the map. To add one, add it to `VIEWS` in `main.js` (and to `TABS` for a sidebar entry). A full-screen map screen also goes into the `.view-map, .view-outing` rule in `app.css`.
- **Each screen gets a fresh `el`.** If the user leaves while a screen is still loading, its late writes land in a detached element, so screens need no "am I still open?" checks.
- **Everything from the API goes through `esc()`** before it reaches `innerHTML`: `user_id` is whatever a device sent.
- **Live data is polled**, as the contract says: `poll(fn, 1500)` waits for each response, backs off after errors, and stops when `fn` returns `false` or when you call the function it returns.
- **Game rules live in the app**, not the API: points, quests, streaks and "you're #5" are computed here from `outings` and `leaderboard`. Keep them in one module (for example `js/game.js`) so every screen agrees.
- **New fields or endpoints** are a contract change: ask in the team chat first.

## Screens

From the mockup, and where each takes its data. The shell already routes to all of them; each fetches its data and shows a placeholder.

| Screen | In the mockup | Data |
|---|---|---|
| Map `#/map` | Spots as dots and clusters with a count; Plogger / Scout switch; "Litter nearby: 27 spots to clean"; start button | `api.spots()`; pollution zones (below) |
| Live outing `#/outing/<id>` | Time, distance, picked; the route; nearest spot; "Just picked · +10 pts"; daily quest progress; Pause / Finish | `api.outing(id)` polled, `api.spots()`. Done: stats, route, picks |
| Summary `#/done/<id>` | The character; "Challenge complete"; points and streak; spots, distance, time; "You're now #3 in Espoo · +2" | `api.outing(id)`, `api.leaderboard()` |
| Feed `#/feed` | This week (time, items, km); the "Clean Otaniemi" challenge; friends' outings with a mini map | `api.outings({ user_id, since: weekStart() })`; `api.outings({ limit })`, `api.outing(id)` for the route |
| Quests `#/quests` | Day streak; daily quests with progress and points; weekly quest | `api.outings({ user_id })` |
| Leaderboard `#/leaders` | This week / All time; podium; "You're #5 · 12 more items to pass Juho"; the list | `api.leaderboard({ since })` |
| Profile `#/profile` | Initials; outings and items; the week by day; the 500 goal ring; badges; the character | `api.outings({ user_id })`, `api.leaderboard()` |

What the API does not have, and how the mockup's parts map onto it:

- **Friends / Espoo / Global** tabs: the API has one global list, no friends or cities.
- **Place names** ("Leppävaara, Espoo"): static text, or reverse geocoding in the app.
- **Litter photos**: an icon by `litter_type`.
- **"Grabber connected", "glasses ready"**: the API does not know device connections; static text.
- **Starting an outing**: the phone records it on a page the API serves; the start button links to `{API_URL}/record?user=<nickname>` ([api/README.md](../api/README.md#recording-an-outing)).

### To do: `#/outing/live`

Today the live screen needs the outing's `session_id` in the address, copied by hand from the phone. For the demo, `#/outing/live` should open the live outing of the selected user by itself:

- Find it with `api.outings({ user_id: currentUser(), limit: 5 })`: outings come newest first, so take the first with `live: true`, and show it as `#/outing/<id>` does. No API change needed.
- With no live outing yet, say so ("No live outing for Anya K. · start one on the phone") and keep looking every few seconds with `poll()`. Then the screen can be opened on the laptop before the walk starts, and the outing appears by itself.
- When the outing ends, go to its summary, `#/done/<id>`.
- The map can show a "Live outing" button that leads here while the selected user has a live outing.

To try it without a phone: open `{API_URL}/record?fake` in another tab, or run `python3 api/sim.py live --user <nickname>`, which prints the outing's id when it starts.

### Pollution zones

The map shows pollution by area (low, medium, high) rather than single items, since a sighting and a later pick are hard to tie to one object. The API is getting `GET /api/zones` for it; until then, compute the same zones from `api.spots()`: cells of about 100 m, counting the spots that are still open. See [ARCHITECTURE.md](../ARCHITECTURE.md#zones).

Map tiles: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
