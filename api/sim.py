#!/usr/bin/env python3
"""Simulated outings for the API: live data for the app without hardware, and a fallback for the demo.

    python3 sim.py live [--user demo] [--role plogger|scout] [--minutes 15] [--speed 1]
    python3 sim.py backfill [--days 5] [--seed 1]
    python3 sim.py record SESSION_ID FILE
    python3 sim.py replay FILE [--speed 1]

The API address comes from API_URL, http://localhost:8000 by default. Only the standard library is used,
so any machine with Python 3.9 or newer runs it, the one on macOS included.
"""

from __future__ import annotations  # Python 3.9 does not know `str | None`

import argparse
import json
import math
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections.abc import Callable
from datetime import datetime, timedelta

API_URL = os.environ.get("API_URL", "http://localhost:8000").rstrip("/")
M_PER_DEG = 111_320  # Metres in a degree of latitude
SPEED_MPS = 1.1  # A plogger's pace, stops included
PLACES = {  # Neighbourhoods of Espoo from the mockup. Otaniemi is next to the Junction venue
    "Otaniemi": (60.1841, 24.8301),
    "Tapiola": (60.1756, 24.8050),
    "Leppävaara": (60.2190, 24.8130),
    "Matinkylä": (60.1600, 24.7380),
    "Niittykumpu": (60.1700, 24.7640),
}
LITTER = {"cigarette_butt": 35, "wrapper": 20, "can": 15, "bottle": 15, "paper": 10, "other": 5}
OTHERS = [  # user, place, role, outings, minutes each: a leaderboard like the one in the mockup
    ("Anya K.", "Otaniemi", "plogger", 3, 45),
    ("Mikko L.", "Tapiola", "plogger", 3, 40),
    ("Sara V.", "Otaniemi", "plogger", 2, 50),
    ("Juho N.", "Tapiola", "plogger", 2, 45),
    ("Liisa P.", "Leppävaara", "plogger", 2, 35),
    ("Otto H.", "Matinkylä", "plogger", 2, 30),
    ("Emma S.", "Niittykumpu", "plogger", 1, 40),
    ("Veera K.", "Otaniemi", "scout", 2, 30),
    ("Ilkka R.", "Otaniemi", "scout", 1, 40),
]

Timeline = "list[tuple[dict, str | None]]"  # Events in time order, each with a line to print when it is sent


def now_ms() -> int:
    return int(time.time() * 1000)


def random_ids() -> Callable[[], str]:
    return lambda: str(uuid.uuid4())


def fixed_ids(seed: str) -> Callable[[], str]:
    """The same ids on every run, so that the API stores a second run once."""
    count = iter(range(10**9))
    return lambda: str(uuid.uuid5(uuid.NAMESPACE_URL, f"plogging-sim/{seed}/{next(count)}"))


def to_latlon(center: tuple[float, float], north: float, east: float) -> tuple[float, float]:
    lat, lon = center
    return lat + north / M_PER_DEG, lon + east / (M_PER_DEG * math.cos(math.radians(lat)))


def walk(rng: random.Random, seconds: int) -> list[tuple[float, float]]:
    """(north, east) in metres for every second of a wandering walk that turns back home halfway."""
    north = east = 0.0
    heading = rng.uniform(0, 2 * math.pi)
    path = [(north, east)]
    for s in range(1, seconds + 1):
        heading += rng.gauss(0, 0.12)
        if s > seconds / 2:
            heading += 0.08 * math.remainder(math.atan2(-east, -north) - heading, 2 * math.pi)
        north += math.cos(heading) * SPEED_MPS
        east += math.sin(heading) * SPEED_MPS
        path.append((north, east))
    return path


def outing(
    rng: random.Random,
    *,
    user: str,
    role: str,
    center: tuple[float, float],
    start_ms: int,
    minutes: float,
    scale: float = 1.0,
    new_id: Callable[[], str] | None = None,
    point_every: int = 1,
    send_every: int = 5,
) -> tuple[Timeline, dict]:
    """One outing as the phone, the grabber and the glasses would send it.

    The glasses see litter 5 to 15 s before the walker reaches it, and the grabber picks it up a few
    seconds later, a few metres off the track. Some litter is picked without being seen, and a scout
    only pins. scale below 1 plays the outing faster than real time.
    """
    new_id = new_id or random_ids()
    seconds = int(minutes * 60)
    path = walk(rng, seconds)
    slug = re.sub(r"[^a-z0-9]+", "-", user.lower()).strip("-") or "user"
    phone, grabber, glasses = (f"sim-{device}-{slug}" for device in ("phone", "grabber", "glasses"))
    session_id = new_id()

    def at(s: float) -> int:
        return start_ms + round(s * 1000 * scale)

    def event(type_: str, s: float, device_id: str, **fields) -> dict:
        return {"event_id": new_id(), "type": type_, "device_id": device_id, "t": at(s), **fields}

    def clock(s: float) -> str:
        return f"{int(s) // 60:2d}:{int(s) % 60:02d}"

    def km(s: float) -> str:
        return f"{s * SPEED_MPS / 1000:.2f} km"

    devices = [glasses] if role == "scout" else [grabber, glasses]
    start = event("session.start", 0, phone, session_id=session_id, user_id=user, role=role, device_ids=devices)
    timeline: Timeline = [(start, f"{clock(0)}  {user} starts a {role} outing: {API_URL}/api/outings/{session_id}")]

    batch, sent_at = [], 0
    for s in range(0, seconds + 1, point_every):
        bad = rng.random() < 0.03  # Now and then GPS jumps, and says so in accuracy
        north, east = path[s]
        lat, lon = to_latlon(center, north + rng.gauss(0, 25 if bad else 2), east + rng.gauss(0, 25 if bad else 2))
        accuracy = rng.uniform(35, 60) if bad else rng.uniform(3, 8)
        batch.append({"t": at(s), "lat": round(lat, 6), "lon": round(lon, 6), "accuracy": round(accuracy, 1)})
        if s - sent_at >= send_every or s == seconds:
            timeline.append((event("track.points", s, phone, session_id=session_id, points=batch), None))
            batch, sent_at = [], s

    seen = picked = seen_and_picked = 0
    s = rng.randint(20, 50)
    while s < seconds - 5:
        kind = rng.choices(list(LITTER), weights=list(LITTER.values()))[0]
        name = kind.replace("_", " ")
        north, east = path[s]
        step_n, step_e = path[s + 1][0] - north, path[s + 1][1] - east
        side = rng.choice((-1, 1)) * rng.uniform(1, 5) / SPEED_MPS  # Metres to the left or right of the track
        litter = (north - step_e * side, east + step_n * side)
        is_seen = role == "scout" or rng.random() < 0.7
        is_picked = role == "plogger" and (not is_seen or rng.random() < 0.9)
        if is_seen:
            seen += 1
            when = max(1, s - rng.randint(5, 15))
            lat, lon = to_latlon(center, litter[0] + rng.gauss(0, 1.5), litter[1] + rng.gauss(0, 1.5))
            fields = {"lat": round(lat, 6), "lon": round(lon, 6), "litter_type": kind, "confidence": 0.9}
            timeline.append((event("sighting", when, glasses, **fields), f"{clock(when)}  glasses pin a {name}"))
        if is_picked:
            picked += 1
            seen_and_picked += is_seen
            when = min(seconds, s + rng.randint(1, 4))
            note = f"{clock(when)}  grabber picks {'a ' + name if is_seen else 'something'}: {picked} picks, {km(when)}"
            timeline.append((event("pick", when, grabber), note))
        s += rng.randint(25, 70) if role == "plogger" else rng.randint(40, 110)

    end = event("session.end", seconds, phone, session_id=session_id)
    timeline.append((end, f"{clock(seconds)}  {user} finishes: {picked} picks, {seen} pins, {km(seconds)}"))
    timeline.sort(key=lambda item: item[0]["t"])
    summary = {
        "session_id": session_id,
        "seen": seen,
        "picked": picked,
        "seen_and_picked": seen_and_picked,
        "distance_m": round(seconds * SPEED_MPS),
    }
    return timeline, summary


def post(events: list[dict]) -> list[dict]:
    """Send events. Returns those to send again later, as a device does after a network error or a 5xx."""
    for i in range(0, len(events), 100):
        body = json.dumps({"events": events[i : i + 100]}).encode()
        request = urllib.request.Request(
            f"{API_URL}/api/events", data=body, headers={"Content-Type": "application/json"}, method="POST"
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                result = json.load(response)
        except urllib.error.HTTPError as e:
            if e.code >= 500:
                print(f"       API answered {e.code}: will send again", file=sys.stderr)
                return events[i:]
            print(f"       API refused the request: {e.code} {e.read()[:300]!r}", file=sys.stderr)
            continue
        except OSError as e:
            print(f"       no connection ({e}): will send again", file=sys.stderr)
            return events[i:]
        for r in result["rejected"]:
            print(f"       rejected {r['event_id']}: {r['error']}", file=sys.stderr)
    return []


def play(timeline: Timeline) -> None:
    """Send each event when its time comes. Ctrl+C ends the outing at once."""
    pending: list[dict] = []
    i = 0
    try:
        while i < len(timeline) or pending:
            while i < len(timeline) and timeline[i][0]["t"] <= now_ms():
                event, note = timeline[i]
                pending.append(event)
                if note:
                    print(note, flush=True)
                i += 1
            if pending:
                pending = post(pending)
            wait = (timeline[i][0]["t"] - now_ms()) / 1000 if i < len(timeline) else 3
            time.sleep(min(1.0, max(0.05, wait)))
    except KeyboardInterrupt:
        ends = [event | {"t": now_ms()} for event, _ in timeline[i:] if event["type"] == "session.end"]
        print(f"\nStopped. Ending the outing now{' (some events are not sent yet)' if pending else ''}.")
        post(pending + ends)


def live(args: argparse.Namespace) -> None:
    rng = random.Random(args.seed)
    timeline, _ = outing(
        rng,
        user=args.user,
        role=args.role,
        center=PLACES[args.place],
        start_ms=now_ms() + 1000,
        minutes=args.minutes,
        scale=1 / args.speed,
    )
    play(timeline)


def backfill(args: argparse.Namespace) -> None:
    """Finished outings of other users over the last days, so that the leaderboard and the map are not empty.
    The same seed sends the same events again, and the API keeps each once."""
    rng = random.Random(args.seed)
    new_id = fixed_ids(f"backfill/{args.seed}")
    midnight = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    events = []
    for user, place, role, count, minutes in OTHERS:
        for _ in range(count):
            length = minutes * rng.uniform(0.7, 1.2)
            start = midnight - timedelta(days=rng.randint(0, args.days - 1)) + timedelta(hours=rng.uniform(8, 19))
            if start + timedelta(minutes=length) > datetime.now():
                start -= timedelta(days=1)
            north, east = rng.uniform(-300, 300), rng.uniform(-300, 300)
            timeline, summary = outing(
                rng,
                user=user,
                role=role,
                center=to_latlon(PLACES[place], north, east),
                start_ms=int(start.timestamp() * 1000),
                minutes=length,
                new_id=new_id,
                point_every=3,
                send_every=60,
            )
            events += [event for event, _ in timeline]
            print(f"{user:9} {role:8} {start:%a %H:%M} {place:12} {summary['picked']:3} picks {summary['seen']:3} pins")
    left = post(events)
    print(f"Sent {len(events) - len(left)} of {len(events)} events to {API_URL}")


def record(args: argparse.Namespace) -> None:
    """Save an outing with the picks and sightings of its devices, from the latest 1000 events of the API."""
    with urllib.request.urlopen(f"{API_URL}/api/events?limit=1000", timeout=30) as response:
        events = json.load(response)["events"]
    start = next((e for e in events if e["type"] == "session.start" and e["session_id"] == args.session_id), None)
    if start is None:
        sys.exit(f"No session.start for {args.session_id} among the latest 1000 events")
    own = [e for e in events if e.get("session_id") == args.session_id]
    end = max([e["t"] for e in own] + [p["t"] for e in own if e["type"] == "track.points" for p in e["points"]])
    devices = set(start["device_ids"])
    window = range(start["t"] - 60_000, end + 60_001)
    nearby = [e for e in events if e["type"] in ("pick", "sighting") and e["device_id"] in devices and e["t"] in window]
    saved = sorted(own + nearby, key=lambda e: e["t"])
    with open(args.file, "w") as f:
        json.dump({"events": saved}, f, indent=1)
    print(f"Saved {len(saved)} events of {start['user_id']}'s outing to {args.file}")


def replay(args: argparse.Namespace) -> None:
    """Send a saved outing again, starting now, with new ids so that it becomes a new outing."""
    with open(args.file) as f:
        events = sorted(json.load(f)["events"], key=lambda e: e["t"])
    first, start = events[0]["t"], now_ms() + 1000
    sessions: dict[str, str] = {}

    def shift(t: int) -> int:
        return start + round((t - first) / args.speed)

    timeline: Timeline = []
    for e in events:
        e = e | {"event_id": str(uuid.uuid4()), "t": shift(e["t"])}
        if "session_id" in e:
            e["session_id"] = sessions.setdefault(e["session_id"], str(uuid.uuid4()))
        if e["type"] == "track.points":
            e["points"] = [p | {"t": shift(p["t"])} for p in e["points"]]
        note = {"session.start": f"replaying as {API_URL}/api/outings/{e.get('session_id')}"}.get(e["type"])
        if e["type"] in ("pick", "sighting", "session.end"):
            note = f"{(e['t'] - start) // 1000:5d}s  {e['type']}"
        timeline.append((e, note))
    play(timeline)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("live", help="an outing in real time, starting now")
    p.add_argument("--user", default="demo", help="shown on the leaderboard, so a nickname")
    p.add_argument("--role", choices=["plogger", "scout"], default="plogger")
    p.add_argument("--place", choices=list(PLACES), default="Otaniemi")
    p.add_argument("--minutes", type=float, default=15)
    p.add_argument("--speed", type=float, default=1, help="10 plays 15 minutes in 1.5")
    p.add_argument("--seed", type=int, help="the same seed walks the same way")
    p.set_defaults(run=live)
    p = commands.add_parser("backfill", help="finished outings of other users over the last days")
    p.add_argument("--days", type=int, default=5)
    p.add_argument("--seed", type=int, default=1)
    p.set_defaults(run=backfill)
    p = commands.add_parser("record", help="save an outing from the API to a file")
    p.add_argument("session_id")
    p.add_argument("file")
    p.set_defaults(run=record)
    p = commands.add_parser("replay", help="send a saved outing again, starting now")
    p.add_argument("file")
    p.add_argument("--speed", type=float, default=1)
    p.set_defaults(run=replay)
    args = parser.parse_args()
    if getattr(args, "speed", 1) <= 0:
        parser.error("--speed must be above 0")
    print(f"API: {API_URL}")
    args.run(args)


if __name__ == "__main__":
    main()
