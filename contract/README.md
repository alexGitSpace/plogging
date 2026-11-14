# Contract

How the parts send data to the API. Draft: change it only together with the team, and announce every change in the team chat.

## Sending events

All devices send events to one endpoint, up to 100 per request:

```http
POST {API_URL}/api/events
Content-Type: application/json

{"events": [ ... ]}
```

`API_URL` is posted in the team chat once the API is up. Locally it is `http://localhost:8000`.

Every event has:

| Field | Meaning |
|---|---|
| `event_id` | A random UUID (v4) made on the device. A resent event keeps it |
| `type` | One of the types below |
| `device_id` | `grabber-1`, `phone-1`, `glasses-1` |
| `t` | When it happened: Unix time in **milliseconds**, UTC (`Date.now()` in JavaScript, `int(time.time() * 1000)` in Python) |

| `type` | Sent by | Extra fields |
|---|---|---|
| `session.start` | phone | `session_id` (a UUID for this outing), `user_id`, `role` (`plogger` or `scout`), `device_ids` (the other devices on this outing, such as `["grabber-1"]`) |
| `track.points` | phone | `session_id`, `points`: a list of `{"t", "lat", "lon", "accuracy"}`, accuracy in metres |
| `pick` | grabber | `confidence` (0 to 1) |
| `sighting` | glasses app | `lat`, `lon`, `litter_type`, `confidence` |
| `session.end` | phone | `session_id` |

- `litter_type` is `bottle`, `can`, `cigarette_butt`, `wrapper`, `paper` or `other`.
- The grabber knows nothing about GPS or outings. The API puts each pick on the route of the outing whose `device_ids` include that grabber, by `t`.

Examples: [outing.json](examples/outing.json) is a short outing with every type; [pick.json](examples/pick.json) and [sighting.json](examples/sighting.json) are single events.

## Recognising a crop

When the glasses cannot name a pick, they send one JPEG. The API asks Gemini and returns a `litter_type`. The glasses then send a normal `sighting`. The JPEG is not stored.

```http
POST {API_URL}/api/recognise
Content-Type: application/json

{"image_jpeg_base64": "...", "hint": "Can", "hint_confidence": 0.41}
```

`hint` and `hint_confidence` are optional. The image must be a JPEG of at most 1 MB once decoded.

`200 OK` with `{"litter_type": "wrapper", "confidence": 0.82}`. `litter_type` is the same set as on a `sighting`. `422` if the image is not a JPEG or is too large. `503` if `GEMINI_API_KEY` is not set. `502` if Gemini fails. After a `4xx` or `5xx`, keep the local label and do not send a sighting for that crop.

## Response

`200 OK` with `{"accepted": ["<event_id>"], "rejected": [{"event_id": "<event_id>", "error": "..."}]}`.

- After a `200`, drop all sent events from your queue. Retrying a rejected event will not help: log its error.
- After a network error or a `5xx`, keep the events and resend them later with the same `event_id`s: the API ignores duplicates. Without a connection, queue events on the device.

## Time

Picks are matched to the route by `t`, so device clocks must be synced over the network. On the UNO Q, take the time on the Linux side: the microcontroller has no real clock. If `t` is missing or clearly wrong, the API uses the arrival time.

## Checking your part

From the repository root:

```bash
export API_URL=http://localhost:8000
curl -X POST "$API_URL/api/events" -H 'Content-Type: application/json' -d @contract/examples/pick.json
curl "$API_URL/api/events?limit=20"   # the latest events, newest first
```

## Not decided yet

- What the interface reads: outing card, stats, leaderboard.
- A `deposit` event from an Atech bag.
