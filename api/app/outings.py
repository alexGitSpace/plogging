"""Outings, built from the event log on every read.

Nothing derived is stored, so events that arrive late (a grabber back on Wi-Fi, a phone that was offline)
and outings recorded before this code existed land on the route by themselves.
"""

import bisect
import math
from collections import defaultdict
from dataclasses import dataclass, field

MAX_ACCURACY_M = 30  # Less accurate track points stay off the route, unless all of them are like that
STEP_M = 10  # Distance grows only after moving this far from the last counted point, so GPS jitter adds nothing
PICK_GRACE_MS = 60_000  # Phone and grabber clocks differ a little: a pick this close to the start or end still counts
IDLE_END_MS = 30 * 60_000  # An outing without session.end is over this long after its last track point
LABEL_RADIUS_M = 30  # A pick takes the litter type of a sighting this close to it,
LABEL_BEFORE_MS = 2 * 60_000  # made up to this long before the pick
LABEL_AFTER_MS = 10_000  # or just after it, since the clocks of the glasses differ too
EARTH_RADIUS_M = 6_371_000


@dataclass
class Outing:
    session_id: str
    started_at: int
    user_id: str | None = None  # None when session.start never arrived
    role: str | None = None
    device_ids: list[str] = field(default_factory=list)
    ended_at: int | None = None  # None until the phone sends session.end
    route: list[dict] = field(default_factory=list)  # Track points {"t", "lat", "lon", "accuracy"} in time order
    picks: list[dict] = field(default_factory=list)  # Pick events with "lat", "lon" and "litter_type" added
    distance_m: float = 0.0


def build_outings(events: list[dict]) -> list[Outing]:
    """Outings from events sorted by t, newest first, with the picks put on their routes."""
    outings: dict[str, Outing] = {}
    tracks: dict[str, list[dict]] = defaultdict(list)
    picks, sightings = [], []
    for e in events:
        if e["type"] == "pick":
            picks.append(e)
        elif e["type"] == "sighting":
            sightings.append(e)
        else:
            o = outings.setdefault(e["session_id"], Outing(e["session_id"], started_at=e["t"]))
            if e["type"] == "session.start" and o.user_id is None:  # A second start with a new event_id changes nothing
                o.user_id, o.role, o.device_ids, o.started_at = e["user_id"], e["role"], e["device_ids"], e["t"]
            elif e["type"] == "track.points":
                tracks[o.session_id] += e["points"]
            elif e["type"] == "session.end":
                o.ended_at = e["t"]
    for o in outings.values():
        o.route = _route(tracks[o.session_id])
        o.distance_m = _distance(o.route)
        if o.user_id is None and o.route:  # session.start is lost: the track tells when the outing began
            o.started_at = min(o.started_at, o.route[0]["t"])
    _put_picks(list(outings.values()), picks)
    _label_picks([p for o in outings.values() for p in o.picks], sightings)
    return sorted(outings.values(), key=lambda o: o.started_at, reverse=True)


def distance_m(a: dict, b: dict) -> float:
    """Great-circle distance in metres between two dicts with "lat" and "lon"."""
    lat1, lon1, lat2, lon2 = map(math.radians, (a["lat"], a["lon"], b["lat"], b["lon"]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def position_at(route: list[dict], t: int) -> dict:
    """Where the outing was at t: between the two route points around t, or at the nearest end of the route."""
    if not route:
        return {"lat": None, "lon": None}
    i = bisect.bisect_left(route, t, key=lambda p: p["t"])
    if i == 0 or i == len(route):
        a = b = route[min(i, len(route) - 1)]
        f = 0.0
    else:
        a, b = route[i - 1], route[i]
        f = (t - a["t"]) / (b["t"] - a["t"])
    return {
        "lat": round(a["lat"] + f * (b["lat"] - a["lat"]), 6),
        "lon": round(a["lon"] + f * (b["lon"] - a["lon"]), 6),
    }


def _route(points: list[dict]) -> list[dict]:
    """Track points in time order, each once (a phone may resend them in a new event), without inaccurate ones."""
    by_t = {p["t"]: p for p in points}
    route = [by_t[t] for t in sorted(by_t)]
    accurate = [p for p in route if p["accuracy"] <= MAX_ACCURACY_M]
    return accurate or route  # Indoors no point may be accurate, and a rough route beats none


def _distance(route: list[dict]) -> float:
    """Walked distance in metres. Standing still to pick something up, GPS points wander around the walker;
    counting only steps away from the last counted point keeps that wandering out."""
    if not route:
        return 0.0
    total, anchor = 0.0, route[0]
    for p in route[1:]:
        d = distance_m(anchor, p)
        if d >= max(STEP_M, p["accuracy"]):
            total, anchor = total + d, p
    return total + distance_m(anchor, route[-1])


def _was_on(o: Outing, t: int) -> bool:
    if o.ended_at is not None:
        end = o.ended_at
    else:  # A forgotten outing must not collect picks from bench tests forever
        end = (o.route[-1]["t"] if o.route else o.started_at) + IDLE_END_MS
    return o.started_at - PICK_GRACE_MS <= t <= end + PICK_GRACE_MS


def _put_picks(outings: list[Outing], picks: list[dict]) -> None:
    """Each pick goes to the outing that lists its grabber and was on at that time; if two were, the later one."""
    for pick in picks:
        on = [o for o in outings if pick["device_id"] in o.device_ids and _was_on(o, pick["t"])]
        if on:
            o = max(on, key=lambda o: o.started_at)
            o.picks.append(pick | position_at(o.route, pick["t"]) | {"litter_type": None})


def _label_picks(picks: list[dict], sightings: list[dict]) -> None:
    """The glasses saw a bottle, then the grabber picked something up right there: the pick is that bottle.
    Each sighting labels at most one pick, the nearest of those made soon after it."""
    used = set()
    for pick in sorted(picks, key=lambda p: p["t"]):
        if pick["lat"] is None:
            continue
        near = [
            (distance_m(pick, s), s)
            for s in sightings
            if s["event_id"] not in used and pick["t"] - LABEL_BEFORE_MS <= s["t"] <= pick["t"] + LABEL_AFTER_MS
        ]
        near = [(d, s) for d, s in near if d <= LABEL_RADIUS_M]
        if near:
            _, s = min(near, key=lambda n: n[0])
            used.add(s["event_id"])
            pick["litter_type"] = s["litter_type"]
