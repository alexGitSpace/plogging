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
| Test data | `?mock` shows Feed, Quests, Leaders and Profile on the test users of `js/mock.js`. Without the API they use them by themselves | Off |
| Fake GPS | `?fake` makes up a walk around Otaniemi while recording, to try it indoors. Its outings are real outings in the API: use a test nickname | Off |

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
| `js/main.js` | Sidebar and router on `#/feed`, `#/quests`, `#/map`, `#/leaders`, `#/profile`, `#/done/<session_id>` |
| `js/api.js` | One function per API request, `poll()` for live screens, `weekStart()` for "this week", and the two settings |
| `js/recorder.js` | Records an outing in this browser and sends it as events: the phone's part of the contract |
| `js/map.js` | A Leaflet map in the light style of the mockup |
| `js/ui.js` | `esc()`, formatting (`km`, `duration`, `clock`, `ago`), litter names and icons |
| `js/game.js` | Game rules: points, streaks, daily and weekly quests, Claim, badges, leaderboard scopes. Also `data`, the API with a fallback to test data |
| `js/mock.js` | Test users: full names, neighbourhoods, friends, people from other cities, and made-up outings when the API is down |
| `js/widgets.js` | Avatars, progress bars, the goal ring, hexagon badges and the SVG mini map of a route |
| `css/screens.css` | Styles of Feed, Quests, Leaderboard and Profile |
| `js/views/` | One module per screen |
| `assets/` | Images and 3D models; keep each file under a few MB |

## Conventions

- **A screen** is a module in `js/views/` with `export default async function render(el, param)`. It fills `el`, and returns a function that cleans up: stops polling, removes the map. To add one, add it to `VIEWS` in `main.js` (and to `TABS` for a sidebar entry). A full-screen map screen also goes into the `.view-map` rules in `app.css`.
- **Each screen gets a fresh `el`.** If the user leaves while a screen is still loading, its late writes land in a detached element, so screens need no "am I still open?" checks.
- **Everything from the API goes through `esc()`** before it reaches `innerHTML`: `user_id` is whatever a device sent.
- **Live data is polled**, as the contract says: `poll(fn, 1500)` waits for each response, backs off after errors, and stops when `fn` returns `false` or when you call the function it returns.
- **Game rules live in the app**, not the API: points, quests, streaks and "you're #5" are computed here from `outings` and `leaderboard`. Keep them in one module (for example `js/game.js`) so every screen agrees.
- **New fields or endpoints** are a contract change: ask in the team chat first.

## Screens

From the mockup, and where each takes its data. Feed, Quests, Leaderboard and Profile are built; names, friends, cities, likes, "Join" and claimed quests are test data or stay in the browser.

| Screen | In the mockup | Data |
|---|---|---|
| Map `#/map` | Spots as dots and clusters with a count; Plogger / Scout switch; "Litter nearby: 27 spots to clean"; start button. While recording: time, points, distance, Pause / Finish | `api.spots()`; pollution zones (below); `recorder.js` and `api.outing(id)` for points |
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

### Recording an outing

**Start outing** on the map records the outing right there, as Strava does: the map follows you and draws your route, and a panel shows time, points and distance with **Pause** and **Finish** (tap twice). Finish goes to the summary, `#/done/<id>`.

- `js/recorder.js` sends `session.start`, the GPS track as `track.points` every 5 seconds, and `session.end`, as the API's `/record` page does ([api/README.md](../api/README.md#recording-an-outing)). The outing is "you" as a plogger with `grabber-1` and `glasses-1`.
- Points are the picks and pins the API puts on the outing (`game.js` rules), polled every 2 seconds; a new pick shows "+10 pts".
- Events wait in the browser until the API accepts them, so a lost connection loses nothing, and a reload carries the outing on. Recording goes on on other tabs of the app; Map shows a red dot meanwhile.
- Pause is the app's own: the timer stops and no track is recorded. The contract has no pause event, so the API's `duration_s` still counts the pause.
- The location needs HTTPS or localhost. Phones stop reporting it when the screen locks, so the page asks to keep the screen on.

### Pollution zones

The map shows pollution by area (low, medium, high) rather than single items, since a sighting and a later pick are hard to tie to one object. The API is getting `GET /api/zones` for it; until then, compute the same zones from `api.spots()`: cells of about 100 m, counting the spots that are still open. See [ARCHITECTURE.md](../ARCHITECTURE.md#zones).

Map tiles: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
