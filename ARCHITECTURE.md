# Architecture

How Plokka is built. Status as of Sunday 15 November 2026, before the submission.

```mermaid
flowchart LR
  grabber[Grabber<br/>Arduino UNO Q] -- pick --> api
  glasses[Glasses app<br/>Meta glasses + phone] -- sighting --> api
  web[Web app<br/>map, outing recorder,<br/>feed, quests, leaderboard] -- session, track --> api
  api[API<br/>events, outings, spots] --> db[(SQLite)]
  api -- spots --> glasses
  api -- outings, spots, leaderboard --> web
```

Every part talks only to the API. The events and the read endpoints are in the [contract](contract/README.md).

## Modules

| Module | Folder | What it does | Status |
|---|---|---|---|
| Contract | `contract/` | Event format and read endpoints | Done |
| API | `api/` | Stores events; builds outings, spots and the leaderboard on every read | Done, deployed |
| Simulator | `api/sim.py` | Sends made-up or recorded outings, for the demo without hardware | Done |
| Grabber | `grabber/` | Recognises the picked item by camera and sends `pick` | Sends `pick` to the API |
| Glasses app | `glasses/` | Streams the glasses camera to the phone, runs YOLO, sends `sighting` with the phone's location | Android: sends `sighting`, shows litter on the Meta Ray-Ban Display. iOS: detection on screen, no sending |
| Outing recorder | `web/` (`js/recorder.js`) | Records the outing on the map, like Strava: start, pause, finish, the GPS track | Done. The API's `/record` page is the older fallback |
| Web app | `web/` | Map, outings, stats, quests, leaderboard, profile | Done, deployed |

### Web app: front end and back end

The API is the back end of the web app; no second server. Points, quests and streaks are computed in the front end from what the API returns, as the contract says. Users are nicknames in `user_id`, no accounts.

Works on desktop and on the phone: below 760 px the sidebar becomes a bottom bar.

### Where a pick is

The grabber has no GPS. The API puts each pick on the route of the outing by time, so a pick is as accurate as the phone's GPS, about 5–15 m. For that, the grabber's clock must be in sync, and the grabber must be in the outing's `device_ids`.
