import json
import uuid
from pathlib import Path

from app.outings import IDLE_END_MS, STEP_M, build, position_at

EXAMPLES = Path(__file__).resolve().parents[2] / "contract" / "examples"
T0 = 1794654000000  # session.start in outing.json
END = T0 + 120_000  # session.end in outing.json
SIGHTING_ID = "6bad6be2-8e7a-46e9-9f19-950499dd251d"  # the bottle in outing.json


def outing_example() -> list[dict]:
    return json.loads((EXAMPLES / "outing.json").read_text())["events"]


def event(type_: str, t: int, device_id: str = "phone-1", **fields) -> dict:
    return {"event_id": str(uuid.uuid4()), "type": type_, "device_id": device_id, "t": t} | fields


def pick(t: int, device_id: str = "grabber-1") -> dict:
    return event("pick", t, device_id)


def start(session_id: str, t: int = T0, devices: tuple = ("grabber-1",), user_id: str = "user-1") -> dict:
    return event("session.start", t, session_id=session_id, user_id=user_id, role="plogger", device_ids=list(devices))


def track(session_id: str, *points: tuple) -> dict:
    """Points as (t, lat, lon)."""
    return event(
        "track.points",
        points[-1][0],
        session_id=session_id,
        points=[{"t": t, "lat": lat, "lon": lon, "accuracy": 5.0} for t, lat, lon in points],
    )


def sighting(t: int, lat: float, lon: float, litter_type: str = "can", device_id: str = "glasses-1") -> dict:
    return event("sighting", t, device_id, lat=lat, lon=lon, litter_type=litter_type, confidence=0.9)


def build_sorted(events: list[dict]):
    return build(sorted(events, key=lambda e: e["t"]))


def test_outing_example():
    log = build_sorted(outing_example())
    [o] = log.outings
    assert (o.user_id, o.role, o.started_at, o.ended_at) == ("user-1", "plogger", T0, END)
    assert len(o.route) == 10
    assert 50 < o.distance_m < 55  # 9 steps of 5.8 m
    # The pick at +65 s lies halfway between the track points at +60 s and +70 s. The glasses saw a bottle
    # there 23 s earlier and 13 m away: the pick cleaned that spot.
    assert o.picks == [
        {"t": T0 + 65_000, "lat": 60.184425, "lon": 24.830295, "litter_type": "bottle", "spot_id": SIGHTING_ID}
    ]
    [spot] = log.spots
    assert (spot["spot_id"], spot["cleaned_at"], spot["cleaned_by"]) == (SIGHTING_ID, T0 + 65_000, "user-1")
    # outing.json does not list glasses-1, so the sighting belongs to no outing.
    assert (spot["spotted_by"], o.pinned) == (None, [])


def test_pick_goes_to_the_outing_that_lists_the_grabber_and_was_on():
    [o] = build_sorted(
        outing_example()
        + [
            pick(T0 + 30_000, device_id="grabber-2"),  # another grabber
            pick(T0 - 5 * 60_000),  # before the outing
            pick(END + 5 * 60_000),  # after it
            pick(T0 - 30_000),  # the grabber's clock is 30 s behind the phone's
        ]
    ).outings
    assert [p["t"] for p in o.picks] == [T0 - 30_000, T0 + 65_000]


def test_newer_outing_takes_the_grabber():
    newer, older = build_sorted([start("s1"), start("s2", t=T0 + 60_000), pick(T0 + 120_000)]).outings
    assert (newer.session_id, len(newer.picks), len(older.picks)) == ("s2", 1, 0)
    assert (newer.picks[0]["lat"], newer.picks[0]["lon"]) == (None, None)  # no track yet


def test_next_outing_does_not_take_the_last_picks_of_the_one_before():
    end = event("session.end", T0 + 600_000, session_id="s1")
    second = start("s2", t=T0 + 620_000)  # started 20 s after the first one ended
    (first,) = [o for o in build_sorted([start("s1"), end, second, pick(T0 + 590_000)]).outings if o.session_id == "s1"]
    assert [p["t"] for p in first.picks] == [T0 + 590_000]


def test_forgotten_outing_does_not_collect_bench_picks():
    log = build_sorted(
        [start("s1"), track("s1", (T0, 60.18, 24.83)), pick(T0 + 20 * 60_000), pick(T0 + IDLE_END_MS + 2 * 60_000)]
    )
    [o] = log.outings
    assert o.ended_at is None
    assert [p["t"] for p in o.picks] == [T0 + 20 * 60_000]


def test_standing_still_adds_no_distance():
    # Ten minutes in one place: the points wander by a few metres, and one wild point is 300 m off.
    points = [
        {"t": T0 + i * 1000, "lat": 60.18 + (i % 3) * 0.00002, "lon": 24.83 + (i % 2) * 0.00004, "accuracy": 5.0}
        for i in range(600)
    ]
    points.append({"t": T0 + 600_000, "lat": 60.183, "lon": 24.83, "accuracy": 80.0})
    points_event = event("track.points", T0 + 600_000, session_id="s1", points=points)
    resent = points_event | {"event_id": str(uuid.uuid4())}  # the same points in a new event
    [o] = build_sorted([points_event, resent]).outings
    assert len(o.route) == 600
    assert o.distance_m < STEP_M
    # session.start never arrived: the track tells when the outing began.
    assert (o.user_id, o.started_at) == (None, T0)


def test_spot_waits_for_a_plogger():
    # A scout with glasses pins a can. An hour later a plogger walks by and picks 8 m from it.
    scout = [
        start("scout", devices=("glasses-2",), user_id="scout-1"),
        track("scout", (T0, 60.18, 24.83), (T0 + 60_000, 60.181, 24.83)),
        sighting(T0 + 30_000, 60.1805, 24.8301, device_id="glasses-2"),
    ]
    hour = 60 * 60_000
    plogger = [
        start("plog", t=T0 + hour, user_id="plogger-1"),
        track("plog", (T0 + hour, 60.1805, 24.83), (T0 + hour + 20_000, 60.1806, 24.8304)),
        pick(T0 + hour + 10_000),  # at 60.18055, 24.8302: 8 m from the can
        pick(T0 + hour + 20_000),  # 20 m from the can, but it is already cleaned
    ]
    log = build_sorted(scout + plogger)
    [spot] = log.spots
    assert (spot["spotted_by"], spot["cleaned_by"], spot["cleaned_at"]) == ("scout-1", "plogger-1", T0 + hour + 10_000)
    plog, scout_outing = log.outings
    assert scout_outing.pinned == [spot]
    assert [(p["litter_type"], p["spot_id"]) for p in plog.picks] == [("can", spot["spot_id"]), (None, None)]


def test_same_litter_seen_twice_is_one_spot():
    log = build_sorted(
        [
            sighting(T0, 60.18, 24.83),
            sighting(T0 + 5_000, 60.18004, 24.83),  # the same can 4 m further: the glasses saw it again
            sighting(T0 + 6_000, 60.18004, 24.83, litter_type="bottle"),  # a bottle next to it
            sighting(T0 + 7_000, 60.1802, 24.83),  # another can 22 m away
        ]
    )
    assert sorted(s["litter_type"] for s in log.spots) == ["bottle", "can", "can"]


def test_each_spot_is_cleaned_once():
    [o] = build_sorted(outing_example() + [pick(T0 + 70_000)]).outings
    assert [p["litter_type"] for p in o.picks] == ["bottle", None]


def test_position_between_and_beyond_the_track():
    route = [{"t": 10, "lat": 1.0, "lon": 2.0}, {"t": 20, "lat": 3.0, "lon": 4.0}]
    assert position_at(route, 0) == {"lat": 1.0, "lon": 2.0}
    assert position_at(route, 15) == {"lat": 2.0, "lon": 3.0}
    assert position_at(route, 99) == {"lat": 3.0, "lon": 4.0}
    assert position_at([], 15) == {"lat": None, "lon": None}
