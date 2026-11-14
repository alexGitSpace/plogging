# api

Receives events from all parts ([contract](../contract/README.md)), stores them, puts picks on the route of each outing and serves the data for the interface.

It stores the events as they come and builds outings, litter spots and the leaderboard from all of them on every read, so late events land in place by themselves. The rules are in the [contract](../contract/README.md#reading) and in `app/outings.py`.

## Run locally

Needs [uv](https://docs.astral.sh/uv/). From this folder:

```bash
uv run uvicorn app.main:app --reload --port 8000   # http://localhost:8000
uv run pytest -q                                   # tests, including every file in contract/examples
uv run ruff format . && uv run ruff check .        # format and lint
```

Or in Docker: `docker build -t plogging-api . && docker run -p 8000:8000 -v plogging-data:/data plogging-api`.

| Variable | Default | Meaning |
|---|---|---|
| `DATABASE_PATH` | `data/events.db` (`/data/events.db` in Docker) | SQLite file with the events |
| `PORT` | `8000` | Port in Docker; hosts usually set it |

## Deploy

The shared API runs on [Railway](https://railway.com) and deploys itself after every push to `main` that changes this folder. Its URL is in the team chat, not in the repository: the API has no authentication yet.

| Railway setting | Value | Why |
|---|---|---|
| Root Directory | `/api` | Builds this folder with its Dockerfile |
| Watch Paths | `/api/**` | Pushes to other folders do not redeploy the API |
| Volume | Mounted at `/data` | Events survive deploys and restarts |
| Region | EU West | Closest to Espoo |

Every deploy stops the API for a few seconds: Railway never runs two copies of a service with a volume. Devices resend what failed, as the contract says.

## What it serves

| Route | What |
|---|---|
| `POST /api/events` | Events as in the contract. Each event is checked on its own: a bad one goes to `rejected` with the reason, the others are stored |
| `GET /api/events?limit=20` | The latest events by arrival, newest first: `{"events": [...]}`, each event as in the contract. `limit` is 1 to 1000 |
| `GET /api/spots` | Litter spots, open and cleaned |
| `GET /api/outings` | Outings with stats; filters `user_id`, `since`, `limit` |
| `GET /api/outings/{session_id}` | One outing with its route, picks and pinned spots |
| `GET /api/leaderboard` | Users by picks; filter `since` |
| `GET /` | A page with the latest 50 events, refreshed every 2 seconds |
| `GET /record` | A phone page that records an outing: see below |
| `GET /docs` | OpenAPI docs |

How the API reads the contract:

- A resent `event_id` is accepted again but stored once.
- A `t` that is missing, before 2026 or more than a day ahead (for example seconds instead of milliseconds) becomes the arrival time.
- Fields outside the contract are dropped.
- `device_id`, `session_id`, `user_id` and each of `device_ids` must be 1 to 100 characters, and `device_ids` can list up to 10 devices. An event that breaks this goes to `rejected`.
- A request that is not `{"events": [...]}` with up to 100 items gets `422` and stores nothing.
- A request body over 1 MB gets `413` and stores nothing. An hour of track at one point per second takes about 250 kB.
- No authentication yet: anyone who knows the URL can post events.

## Recording an outing

`/record` on the API is the phone's part of the contract: open it in the phone's browser, enter a nickname, choose plogger or scout and the devices on this outing, and press Start. It sends `session.start`, the GPS track as `track.points` every 5 seconds, and `session.end` on Finish (tap twice).

- The API serves it so that it gets the API's HTTPS, which browsers require for the location, and needs no API address.
- Events wait in the browser until the API accepts them and keep their `event_id` when resent: a lost connection or a reloaded page loses nothing, and a reload carries the outing on.
- It asks the phone to keep the screen on. Phones stop reporting the location when the screen locks, so keep the page open.
- The web app can link to it as `{API_URL}/record?user=<nickname>` to fill in the nickname.
- `/record?fake` makes up a walk around Otaniemi instead of reading the GPS, to try it indoors. Its outings are real outings in the API: use a test nickname.
- The phone's `device_id` is `phone-` and 8 random characters, kept in the browser.

## Simulator

`sim.py` sends made-up outings to `API_URL`, so the app gets live data without hardware. It needs only Python 3.9 or newer, no packages:

```bash
export API_URL=http://localhost:8000
python3 sim.py live --user demo --minutes 15   # an outing in real time: the track, glasses pinning litter, the grabber picking it
python3 sim.py live --role scout --speed 10    # a scout only pins litter, ten times faster
python3 sim.py backfill                        # outings of 9 other users over the last 5 days, for the leaderboard and the map
python3 sim.py record <session_id> walk.json   # save a real outing with the picks and sightings of its devices
python3 sim.py replay walk.json                # send it again from now as a new outing, if the hardware fails on stage
```

- Ctrl+C ends a live outing at once with `session.end`.
- Simulated devices are named `sim-phone-…`, `sim-grabber-…` and `sim-glasses-…`, so they never take picks from real devices, and their events are easy to find and delete.
- `backfill` with the same `--seed` sends the same events, and the API stores them once.
- On the shared API a live outing fills the events page at `/` and hides real devices there: run it locally when you can.
