"""Outings and litter spots, built from the event log on every read.

Nothing derived is stored, so events that arrive late (a grabber back on Wi-Fi, a phone that was offline)
and outings recorded before this code existed land in place by themselves.
"""

import bisect
import math
from collections import Counter, defaultdict
from dataclasses import dataclass, field

MAX_ACCURACY_M = 30  # Less accurate track points stay off the route, unless all of them are like that
STEP_M = 10  # Distance grows only after moving this far from the last counted point, so GPS jitter adds nothing
GRACE_MS = 60_000  # Device clocks differ a little: an event this close to an outing's start or end still belongs to it
IDLE_END_MS = 30 * 60_000  # An outing without session.end is over this long after its last track point
SPOT_MERGE_M = 10  # A sighting this close to an open spot of the same type is the same litter seen again
RECENT_MS = 2 * 60_000  # A pick cleans what the glasses of its outing saw this recently...
RECENT_RADIUS_M = 100  # ...this close by GPS: the phone of the outing and the phone of the glasses differ by tens of m
CLEAN_RADIUS_M = 30  # Otherwise a pick this close to an open spot cleans it: phone and glasses GPS are both metres off
HELD_MS = 60_000  # After a pick, the outing's glasses may see that litter in the grabber or the bag for this long
SKEW_MS = 10_000  # A pick may carry a time slightly before the sighting of the same litter
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
    picks: list[dict] = field(default_factory=list)  # {"t", "lat", "lon", "litter_type", "spot_id"} in time order
    pinned: list[dict] = field(default_factory=list)  # Spots first seen by the glasses of this outing
    distance_m: float = 0.0

    @property
    def open_until(self) -> int:
        """Without session.end the outing is over IDLE_END_MS after its last track point."""
        return (self.route[-1]["t"] if self.route else self.started_at) + IDLE_END_MS

    def was_on(self, t: int, grace_ms: int = 0) -> bool:
        end = self.ended_at if self.ended_at is not None else self.open_until
        return self.started_at - grace_ms <= t <= end + grace_ms


@dataclass
class Log:
    outings: list[Outing]  # Newest first
    spots: list[dict]  # Newest first


def build(events: list[dict]) -> Log:
    """Outings and spots from events sorted by t."""
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
    timeline = []  # (t, kind, event, outing)
    for p in picks:
        if o := _owner(outings.values(), p):
            pick = {"t": p["t"]} | position_at(o.route, p["t"]) | {"litter_type": p.get("litter_type"), "spot_id": None}
            o.picks.append(pick)
            timeline.append((p["t"], "pick", pick, o))
    for s in sightings:  # Moved earlier by SKEW_MS, so that a pick stamped just before its sighting still finds it
        timeline.append((s["t"] - SKEW_MS, "sighting", s, _owner(outings.values(), s)))
    spots = _spots(sorted(timeline, key=lambda x: (x[0], x[1])))
    return Log(
        outings=sorted(outings.values(), key=lambda o: o.started_at, reverse=True),
        spots=sorted(spots, key=lambda s: s["spotted_at"], reverse=True),
    )


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


def stats(outings: list[Outing], now_ms: int) -> dict:
    """Totals over outings, as the app shows them for one outing, a week or a user."""
    litter = Counter(p["litter_type"] for o in outings for p in o.picks if p["litter_type"])
    return {
        "duration_s": sum(_duration_s(o, now_ms) for o in outings),
        "distance_m": round(sum(o.distance_m for o in outings)),
        "picks": sum(len(o.picks) for o in outings),
        "spots_cleaned": sum(1 for o in outings for p in o.picks if p["spot_id"]),
        "spots_pinned": sum(len(o.pinned) for o in outings),
        "litter": dict(sorted(litter.items())),
    }


def is_live(o: Outing, now_ms: int) -> bool:
    return o.ended_at is None and now_ms <= o.open_until


def _duration_s(o: Outing, now_ms: int) -> int:
    if o.ended_at is not None:
        end = o.ended_at
    elif is_live(o, now_ms):
        end = now_ms
    else:  # Abandoned: it lasted until the last thing it recorded
        end = max([o.started_at] + [p["t"] for p in o.route[-1:] + o.picks[-1:]])
    return max(0, end - o.started_at) // 1000


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


def _owner(outings, e: dict) -> Outing | None:
    """The outing that lists the device of the event and was on at that time; if two were, the later one.
    The minute of grace for device clocks counts only when no outing was on: otherwise an outing started
    right after another would take the last picks of the one before."""
    listed = [o for o in outings if e["device_id"] in o.device_ids]
    for grace_ms in (0, GRACE_MS):
        if on := [o for o in listed if o.was_on(e["t"], grace_ms)]:
            return max(on, key=lambda o: o.started_at)
    return None


def _spots(timeline: list[tuple]) -> list[dict]:
    """Spots are litter the glasses saw. A pick cleans one (see _cleaned_by) and takes its litter type: the glasses
    see better than the grabber's camera. A pick that cleans nothing keeps the type the grabber sent."""
    spots: list[dict] = []
    open_spots: list[dict] = []
    seen: dict[str, dict[str, int]] = {}  # spot_id: {session_id: t of the latest sighting of it on that outing}
    held: list[tuple[dict, str]] = []  # Spots cleaned in the last HELD_MS, with the session_id of the pick
    for _, kind, e, o in timeline:
        if kind == "sighting":
            held = [(s, sid) for s, sid in held if e["t"] - s["cleaned_at"] <= HELD_MS]
            if o and any(sid == o.session_id and _same_litter(s, e) for s, sid in held):
                continue  # The litter its grabber has just picked, seen again in the grabber or the bag
            same = [s for s in open_spots if _same_litter(s, e)]
            if same:
                if o:
                    for s in same:
                        seen.setdefault(s["spot_id"], {})[o.session_id] = e["t"]
                continue
            spot = {
                "spot_id": e["event_id"],
                "lat": e["lat"],
                "lon": e["lon"],
                "litter_type": e["litter_type"],
                "spotted_at": e["t"],
                "spotted_by": o.user_id if o else None,
                "cleaned_at": None,
                "cleaned_by": None,
            }
            spots.append(spot)
            open_spots.append(spot)
            if o:
                seen[spot["spot_id"]] = {o.session_id: e["t"]}
                o.pinned.append(spot)
        elif spot := _cleaned_by(e, o, open_spots, seen):
            open_spots.remove(spot)
            held.append((spot, o.session_id))
            spot["cleaned_at"], spot["cleaned_by"] = e["t"], o.user_id
            e["litter_type"], e["spot_id"] = spot["litter_type"], spot["spot_id"]
    return spots


def _same_litter(spot: dict, sighting: dict) -> bool:
    """A sighting this close to a spot of its type is the same litter seen again. Not further: litter of one type
    often lies close together, such as cigarette butts."""
    return spot["litter_type"] == sighting["litter_type"] and distance_m(spot, sighting) <= SPOT_MERGE_M


def _cleaned_by(pick: dict, o: Outing, open_spots: list[dict], seen: dict) -> dict | None:
    """The open spot a pick cleans. First one that the glasses of its outing saw in the last RECENT_MS, up to
    RECENT_RADIUS_M away by GPS: of the type the grabber sent, if any, then the nearest, or the latest seen while the
    pick has no position. Else the nearest open spot within CLEAN_RADIUS_M, such as one a scout pinned."""

    def seen_at(s: dict) -> int | None:
        return seen.get(s["spot_id"], {}).get(o.session_id)

    recent = [s for s in open_spots if (t := seen_at(s)) is not None and pick["t"] - t <= RECENT_MS]
    if pick["lat"] is not None:
        recent = [s for s in recent if distance_m(s, pick) <= RECENT_RADIUS_M]
    if recent:

        def rank(s: dict) -> tuple:
            other_type = pick["litter_type"] is not None and s["litter_type"] != pick["litter_type"]
            return other_type, distance_m(s, pick) if pick["lat"] is not None else -seen_at(s)

        return min(recent, key=rank)
    if pick["lat"] is None:
        return None
    near = [(d, s) for s in open_spots if (d := distance_m(s, pick)) <= CLEAN_RADIUS_M]
    return min(near, key=lambda n: n[0])[1] if near else None
