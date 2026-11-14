import json
import uuid
from pathlib import Path

from app.outings import IDLE_END_MS, STEP_M, build_outings, position_at

EXAMPLES = Path(__file__).resolve().parents[2] / "contract" / "examples"
T0 = 1794654000000  # session.start in outing.json
END = T0 + 120_000  # session.end in outing.json


def outing_example() -> list[dict]:
    return json.loads((EXAMPLES / "outing.json").read_text())["events"]


def event(type_: str, t: int, device_id: str = "phone-1", **fields) -> dict:
    return {"event_id": str(uuid.uuid4()), "type": type_, "device_id": device_id, "t": t} | fields


def pick(t: int, device_id: str = "grabber-1") -> dict:
    return event("pick", t, device_id, confidence=0.9)


def start(session_id: str, t: int = T0, devices: tuple = ("grabber-1",)) -> dict:
    return event("session.start", t, session_id=session_id, user_id="user-1", role="plogger", device_ids=list(devices))


def build(events: list[dict]):
    return build_outings(sorted(events, key=lambda e: e["t"]))


def test_outing_example():
    [o] = build(outing_example())
    assert (o.user_id, o.role, o.started_at, o.ended_at) == ("user-1", "plogger", T0, END)
    assert len(o.route) == 10
    assert 50 < o.distance_m < 55  # 9 steps of 5.8 m
    [p] = o.picks
    # The pick at +65 s lies halfway between the track points at +60 s and +70 s.
    assert (p["lat"], p["lon"]) == (60.184425, 24.830295)
    # The glasses saw a bottle there 23 s earlier and 13 m away.
    assert p["litter_type"] == "bottle"


def test_pick_goes_to_the_outing_that_lists_the_grabber_and_was_on():
    [o] = build(
        outing_example()
        + [
            pick(T0 + 30_000, device_id="grabber-2"),  # another grabber
            pick(T0 - 5 * 60_000),  # before the outing
            pick(END + 5 * 60_000),  # after it
            pick(T0 - 30_000),  # the grabber's clock is 30 s behind the phone's
        ]
    )
    assert [p["t"] for p in o.picks] == [T0 - 30_000, T0 + 65_000]


def test_newer_outing_takes_the_grabber():
    newer, older = build([start("s1"), start("s2", t=T0 + 60_000), pick(T0 + 120_000)])
    assert (newer.session_id, len(newer.picks), len(older.picks)) == ("s2", 1, 0)
    assert (newer.picks[0]["lat"], newer.picks[0]["lon"]) == (None, None)  # no track yet


def test_forgotten_outing_does_not_collect_bench_picks():
    point = {"t": T0, "lat": 60.18, "lon": 24.83, "accuracy": 5.0}
    track = event("track.points", T0, session_id="s1", points=[point])
    [o] = build([start("s1"), track, pick(T0 + 20 * 60_000), pick(T0 + IDLE_END_MS + 2 * 60_000)])
    assert o.ended_at is None
    assert [p["t"] for p in o.picks] == [T0 + 20 * 60_000]


def test_standing_still_adds_no_distance():
    # Ten minutes in one place: the points wander by a few metres, and one wild point is 300 m off.
    points = [
        {"t": T0 + i * 1000, "lat": 60.18 + (i % 3) * 0.00002, "lon": 24.83 + (i % 2) * 0.00004, "accuracy": 5.0}
        for i in range(600)
    ]
    points.append({"t": T0 + 600_000, "lat": 60.183, "lon": 24.83, "accuracy": 80.0})
    track = event("track.points", T0 + 600_000, session_id="s1", points=points)
    resent = track | {"event_id": str(uuid.uuid4())}  # the same points in a new event
    [o] = build([track, resent])
    assert len(o.route) == 600
    assert o.distance_m < STEP_M
    # session.start never arrived: the track tells when the outing began.
    assert (o.user_id, o.started_at) == (None, T0)


def test_each_sighting_labels_one_pick():
    [o] = build(outing_example() + [pick(T0 + 70_000)])
    assert [p["litter_type"] for p in o.picks] == ["bottle", None]


def test_position_between_and_beyond_the_track():
    route = [{"t": 10, "lat": 1.0, "lon": 2.0}, {"t": 20, "lat": 3.0, "lon": 4.0}]
    assert position_at(route, 0) == {"lat": 1.0, "lon": 2.0}
    assert position_at(route, 15) == {"lat": 2.0, "lon": 3.0}
    assert position_at(route, 99) == {"lat": 3.0, "lon": 4.0}
    assert position_at([], 15) == {"lat": None, "lon": None}
