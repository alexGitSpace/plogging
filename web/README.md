# web

What people see: the map of litter spots, outings, stats, quests, the leaderboard and the live outing screen. It reads everything from the API ([contract](../contract/README.md)). Desktop first; the screens follow the Figma mockup.

Plain HTML and ES modules, no build step. Leaflet draws the maps and Three.js the animated character; both load from a CDN.

## Run locally

```bash
cd web && python3 -m http.server 5173   # http://localhost:5173
```

| Setting | How | Default |
|---|---|---|
| API address | `?api=https://…` in the page address, remembered in the browser; or `js/config.js` (copy `js/config.example.js`, not in git) | `http://localhost:8000` |
| You | `?user=<nickname>`, or the selector at the bottom of the sidebar | The leader of the leaderboard |

The API address stays out of git: the API has no authentication yet. Ask in the team chat.

## Layout

| Path | What it is |
|---|---|
| `index.html` | The shell: sidebar and the view |
| `css/app.css` | Colours and shapes from the mockup |
| `js/main.js` | Router on `#/feed`, `#/quests`, `#/map`, `#/leaders`, `#/profile`, `#/outing/<session_id>`, `#/done/<session_id>` |
| `js/api.js` | Requests to the API and the two settings |
| `js/map.js` | A Leaflet map in the light style of the mockup |
| `js/ui.js` | Formatting, icons and `esc()`: everything from the API is escaped before it reaches the page |
| `js/views/` | One module per screen: `render(el, param)` returns a cleanup function, for example to stop polling |

Map tiles: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
