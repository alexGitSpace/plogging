# api

Receives events from all parts ([contract](../contract/README.md)), stores them, puts picks on the route of each outing and serves the data for the interface.

Now a stub: it validates and stores events and shows the latest ones. Matching picks to the route, the map and stats come next.

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

## What it serves

| Route | What |
|---|---|
| `POST /api/events` | Events as in the contract. Each event is checked on its own: a bad one goes to `rejected` with the reason, the others are stored |
| `GET /api/events?limit=20` | The latest events by arrival, newest first: `{"events": [...]}`, each event as in the contract. `limit` is 1 to 1000 |
| `GET /` | A page with the latest 50 events, refreshed every 2 seconds |
| `GET /docs` | OpenAPI docs |

How the stub reads the contract:

- A resent `event_id` is accepted again but stored once.
- A `t` that is missing, before 2026 or more than a day ahead (for example seconds instead of milliseconds) becomes the arrival time.
- Fields outside the contract are dropped.
- A request that is not `{"events": [...]}` with up to 100 items gets `422` and stores nothing.
- No authentication yet: anyone who knows the URL can post events.
