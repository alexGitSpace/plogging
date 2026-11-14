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
| `pick` | grabber | None: the grabber only reports that it picked something |
| `sighting` | glasses app | `lat`, `lon`, `litter_type`, `confidence` |
| `session.end` | phone | `session_id` |

- `litter_type` is `bottle`, `can`, `cigarette_butt`, `wrapper`, `paper` or `other`.
- The grabber knows nothing about GPS or outings. The API puts each pick on the route of the outing whose `device_ids` include that grabber, by `t`.

Examples: [outing.json](examples/outing.json) is a short outing with every type; [pick.json](examples/pick.json) and [sighting.json](examples/sighting.json) are single events.

## Response

`200 OK` with `{"accepted": ["<event_id>"], "rejected": [{"event_id": "<event_id>", "error": "..."}]}`.

- After a `200`, drop all sent events from your queue. Retrying a rejected event will not help: log its error.
- After a network error or a `5xx`, keep the events and resend them later with the same `event_id`s: the API ignores duplicates. Without a connection, queue events on the device.

## Time

Picks are matched to the route by `t`, so device clocks must be synced over the network. On the UNO Q, take the time on the Linux side: the microcontroller has no real clock. If `t` is missing or clearly wrong, the API uses the arrival time.

## Reading

The app and the glasses read what the API builds from the events. It is computed from all events on every request, so late events show up by themselves. For live data, poll: the live screen asks for its outing every 1–2 seconds.

| Request | Returns |
|---|---|
| `GET /api/spots` | Litter spots, newest first: where the glasses saw litter and whether someone picked it up |
| `GET /api/outings?user_id=&since=&limit=` | Outings, newest first, with their `stats`. `limit` is 50 by default, up to 500 |
| `GET /api/outings/{session_id}` | One outing with its `route`, the `picks` on it and the spots its glasses `pinned` |
| `GET /api/leaderboard?since=` | Users ranked by picks, then by distance |

`since` is Unix time in milliseconds: only outings started then or later count. For "this week", pass the start of the week.

How the API puts events together:

- An outing takes the picks of the grabbers and the sightings of the glasses in its `device_ids`, from `session.start` to `session.end`, give or take a minute for device clocks. Without `session.end` the outing is over 30 minutes after its last track point; until then `live` is true.
- A pick sits on the route at its `t`, between the track points around it.
- A sighting makes a spot, unless an open spot with the same `litter_type` lies within 10 m: that is the same litter seen again.
- A pick within 30 m of an open spot cleans the nearest one. The spot gets `cleaned_at` and `cleaned_by`, and the pick gets the spot's `litter_type` and `spot_id`. Other picks have `litter_type: null`.
- `stats` has `duration_s`, `distance_m` (without inaccurate points and GPS jitter), `picks`, `spots_cleaned`, `spots_pinned` and `litter`: the picks that cleaned a spot, by litter type.
- The leaderboard shows `user_id` as it is, so use a nickname.
- Points, quests, streaks and "you are #5" are up to the app.

Examples: the responses for [outing.json](examples/outing.json) are in [examples/responses](examples/responses).

## Checking your part

From the repository root:

```bash
export API_URL=http://localhost:8000
curl -X POST "$API_URL/api/events" -H 'Content-Type: application/json' -d @contract/examples/pick.json
curl "$API_URL/api/events?limit=20"   # the latest events, newest first
```

## Not decided yet

- Sending a camera frame so that the API recognises the litter type.
- A `deposit` event from an Atech bag.
