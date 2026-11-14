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

### Pollution zones

The map shows pollution by area (low, medium, high) rather than single items, since a sighting and a later pick are hard to tie to one object. The API is getting `GET /api/zones` for it; until then, compute the same zones from `api.spots()`: cells of about 100 m, counting the spots that are still open. See [ARCHITECTURE.md](../ARCHITECTURE.md#zones).

Map tiles: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
