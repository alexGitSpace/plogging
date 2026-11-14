import random

import sim
from app.events import parse_event
from app.outings import build

T = 1794654000000


def simulate(role: str, seed: int, minutes: float = 20):
    timeline, summary = sim.outing(
        random.Random(seed), user="tester", role=role, center=sim.PLACES["Otaniemi"], start_ms=T, minutes=minutes
    )
    events = [event for event, _ in timeline]
    assert all(parse_event(event, now_ms=T)[1] is None for event in events)
    assert [e["t"] for e in events] == sorted(e["t"] for e in events)
    return build(events), summary


def test_plogger_cleans_the_spots_the_glasses_pinned():
    log, summary = simulate("plogger", seed=3)
    [o] = log.outings
    assert (o.user_id, o.ended_at) == ("tester", T + 20 * 60_000)
    assert len(o.picks) == summary["picked"] > 10
    cleaned = sum(1 for p in o.picks if p["spot_id"])
    assert summary["seen_and_picked"] * 0.9 <= cleaned <= summary["picked"]
    # The walk may pass the same place twice: a second sighting of the same type there is the same spot.
    assert summary["seen"] * 0.9 <= len(o.pinned) == len(log.spots) <= summary["seen"]
    # GPS noise adds a few percent.
    assert 0.8 * summary["distance_m"] < o.distance_m < 1.2 * summary["distance_m"]


def test_scout_only_pins():
    log, summary = simulate("scout", seed=4, minutes=15)
    [o] = log.outings
    assert o.picks == []
    assert len(log.spots) == summary["seen"] > 5
    assert all(s["cleaned_at"] is None and s["spotted_by"] == "tester" for s in log.spots)


def test_speed_up_keeps_the_outing_whole():
    timeline, _ = sim.outing(
        random.Random(5), user="tester", role="plogger", center=sim.PLACES["Tapiola"], start_ms=T, minutes=10, scale=0.1
    )
    assert timeline[-1][0]["type"] == "session.end"
    assert timeline[-1][0]["t"] - timeline[0][0]["t"] == 60_000  # ten minutes in one
