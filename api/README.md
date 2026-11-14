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
| `GET /docs` | OpenAPI docs |

How the stub reads the contract:

- A resent `event_id` is accepted again but stored once.
- A `t` that is missing, before 2026 or more than a day ahead (for example seconds instead of milliseconds) becomes the arrival time.
- Fields outside the contract are dropped.
- `device_id`, `session_id`, `user_id` and each of `device_ids` must be 1 to 100 characters, and `device_ids` can list up to 10 devices. An event that breaks this goes to `rejected`.
- A request that is not `{"events": [...]}` with up to 100 items gets `422` and stores nothing.
- A request body over 1 MB gets `413` and stores nothing. An hour of track at one point per second takes about 250 kB.
- No authentication yet: anyone who knows the URL can post events.
