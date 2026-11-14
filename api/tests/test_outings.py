import json
import uuid
from pathlib import Path

from app.outings import HELD_MS, IDLE_END_MS, RECENT_MS, STEP_M, build, position_at, stats

EXAMPLES = Path(__file__).resolve().parents[2] / "contract" / "examples"
T0 = 1794654000000  # session.start in outing.json
END = T0 + 120_000  # session.end in outing.json
SIGHTING_ID = "6bad6be2-8e7a-46e9-9f19-950499dd251d"  # the bottle in outing.json
KIT = ("grabber-1", "glasses-1")  # A plogger with a grabber and glasses
HOUR = 60 * 60_000


def outing_example() -> list[dict]:
    return json.loads((EXAMPLES / "outing.json").read_text())["events"]


def event(type_: str, t: int, device_id: str = "phone-1", **fields) -> dict:
    return {"event_id": str(uuid.uuid4()), "type": type_, "device_id": device_id, "t": t} | fields


def pick(t: int, device_id: str = "grabber-1", **fields) -> dict:
    return event("pick", t, device_id, **fields)


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


def test_pick_keeps_its_own_type_unless_it_cleans_a_spot():
    # outing.json: the pick at +65 s cleans the bottle spot. The grabber's own guess loses to the glasses.
    events = [e | {"litter_type": "can"} if e["type"] == "pick" else e for e in outing_example()]
    events += [pick(T0 + 90_000, litter_type="paper"), pick(T0 + 100_000)]  # the bottle is gone: nothing to clean
    log = build_sorted(events)
    [o] = log.outings
    assert [(p["litter_type"], p["spot_id"]) for p in o.picks] == [
        ("bottle", SIGHTING_ID),
        ("paper", None),
        (None, None),
    ]
    assert stats([o], END)["litter"] == {"bottle": 1, "paper": 1}


def kit_outing(*events: dict, until: int = T0 + 600_000) -> list[dict]:
    """An outing with a grabber and glasses that stands at 60.18, 24.83 from T0 to until."""
    return [start("s1", devices=KIT), track("s1", (T0, 60.18, 24.83), (until, 60.18, 24.83)), *events]


def test_pick_cleans_what_its_glasses_just_saw_however_far_gps_puts_it():
    # The phone that records the outing and the phone of the glasses may disagree by tens of metres indoors.
    # An old can lies 10 m from the pick, but the glasses saw a bottle 30 s before it, 60 m away by GPS.
    log = build_sorted(
        [sighting(T0 - HOUR, 60.18009, 24.83, device_id="glasses-2")]
        + kit_outing(sighting(T0 + 30_000, 60.18054, 24.83, litter_type="bottle"), pick(T0 + 60_000))
    )
    bottle, can = log.spots
    assert (bottle["litter_type"], bottle["cleaned_at"], can["cleaned_at"]) == ("bottle", T0 + 60_000, None)


def test_pick_takes_the_type_the_grabber_sent_then_the_nearest():
    log = build_sorted(
        kit_outing(
            sighting(T0 + 10_000, 60.18005, 24.83),  # a can 6 m away
            sighting(T0 + 11_000, 60.18018, 24.83, litter_type="bottle"),  # a bottle 20 m away
            sighting(T0 + 12_000, 60.18023, 24.83, litter_type="paper"),  # paper 26 m away
            pick(T0 + 30_000, litter_type="bottle"),
            pick(T0 + 40_000),
        )
    )
    [o] = log.outings
    assert [p["litter_type"] for p in o.picks] == ["bottle", "can"]
    assert [s["litter_type"] for s in log.spots if s["cleaned_at"] is None] == ["paper"]


def test_pick_before_any_track_cleans_the_latest_thing_its_glasses_saw():
    log = build_sorted(
        [
            start("s1", devices=KIT),
            sighting(T0 + 10_000, 60.18, 24.83),
            sighting(T0 + 20_000, 60.181, 24.83, litter_type="bottle"),
            pick(T0 + 30_000),
            pick(T0 + 40_000),
        ]
    )
    [o] = log.outings
    assert [(p["lat"], p["litter_type"]) for p in o.picks] == [(None, "bottle"), (None, "can")]


def test_from_afar_only_what_its_own_glasses_saw_lately_counts():
    later = T0 + 10_000 + RECENT_MS + 10_000
    log = build_sorted(
        kit_outing(
            sighting(T0 + 10_000, 60.18054, 24.83),  # 60 m away, seen by its glasses too long before the pick
            sighting(later - 5_000, 60.18054, 24.8302, litter_type="bottle", device_id="glasses-2"),  # other glasses
            pick(later),
        )
    )
    assert [s["cleaned_at"] for s in log.spots] == [None, None]


def test_litter_seen_again_counts_as_seen_now():
    # A scout pinned a can an hour ago. The plogger's glasses see it again, and GPS puts the pick 60 m away.
    log = build_sorted(
        [sighting(T0 - HOUR, 60.18054, 24.83, device_id="glasses-2")]
        + kit_outing(sighting(T0 + 30_000, 60.18057, 24.83), pick(T0 + 60_000))  # 3 m from the pin: the same can
    )
    [spot] = log.spots
    assert spot["cleaned_at"] == T0 + 60_000


def test_litter_in_the_grabber_is_not_pinned_again():
    soon, later = T0 + 20_000 + HELD_MS // 2, T0 + 20_000 + HELD_MS + 10_000
    log = build_sorted(
        kit_outing(
            sighting(T0 + 10_000, 60.18, 24.83),
            pick(T0 + 20_000),
            sighting(T0 + 25_000, 60.18002, 24.83),  # seen as it was picked: still the same can
            sighting(soon, 60.18005, 24.83),  # the can in the grabber, 6 m off by GPS
            sighting(soon, 60.18005, 24.83, litter_type="bottle"),  # a bottle there is new litter
            sighting(soon + 1_000, 60.18008, 24.83, device_id="glasses-2"),  # someone else's glasses: a new can
            sighting(later, 60.17992, 24.83),  # long after the pick: another can, 9 m south of the picked one
        )
    )
    assert [(s["litter_type"], s["spotted_at"], s["cleaned_at"]) for s in log.spots] == [
        ("can", later, None),
        ("can", soon + 1_000, None),
        ("bottle", soon, None),
        ("can", T0 + 10_000, T0 + 20_000),
    ]


def test_what_its_glasses_saw_far_away_is_not_taken_for_the_pick():
    # The glasses saw a bottle 150 m away and it was left. The pick lands 3 m from a can a scout pinned.
    log = build_sorted(
        [sighting(T0 - HOUR, 60.18003, 24.83, device_id="glasses-2")]
        + kit_outing(sighting(T0 + 30_000, 60.18135, 24.83, litter_type="bottle"), pick(T0 + 90_000))
    )
    bottle, can = log.spots
    assert (bottle["cleaned_at"], can["cleaned_at"]) == (None, T0 + 90_000)


def test_what_the_glasses_saw_on_an_earlier_outing_does_not_count():
    # A demo stand: user-1 finishes, user-2 starts with the same grabber and glasses and picks something else.
    log = build_sorted(
        [
            start("s1", devices=KIT),
            track("s1", (T0, 60.18, 24.83), (T0 + 60_000, 60.18, 24.83)),
            sighting(T0 + 30_000, 60.18054, 24.83),  # user-1's glasses, 60 m off by GPS
            event("session.end", T0 + 60_000, session_id="s1"),
            start("s2", t=T0 + 90_000, devices=KIT, user_id="user-2"),
            track("s2", (T0 + 90_000, 60.18, 24.83), (T0 + 120_000, 60.18, 24.83)),
            pick(T0 + 120_000),
        ]
    )
    [spot] = log.spots
    assert spot["cleaned_at"] is None


def test_another_kit_seeing_the_same_can_keeps_this_outings_claim():
    # Two ploggers walk together with their own kits, and both glasses see the can. The first one picks it.
    log = build_sorted(
        kit_outing(sighting(T0 + 10_000, 60.18054, 24.83))  # 60 m off by GPS
        + [
            start("s2", devices=("grabber-2", "glasses-2"), user_id="user-2"),
            sighting(T0 + 20_000, 60.18056, 24.83, device_id="glasses-2"),
            pick(T0 + 40_000),
        ]
    )
    [spot] = log.spots
    assert (spot["cleaned_at"], spot["cleaned_by"]) == (T0 + 40_000, "user-1")


def test_litter_in_the_grabber_does_not_keep_litter_next_to_it_fresh():
    # Two butts 15 m apart, 60 m off by GPS. The first is picked; the glasses then see it in the grabber, 7 m from the
    # second. That is not a new look at the second one, so a pick two minutes later does not take it from afar.
    butt = "cigarette_butt"
    log = build_sorted(
        kit_outing(
            sighting(T0 + 10_000, 60.18054, 24.83, litter_type=butt),
            sighting(T0 + 10_000, 60.180675, 24.83, litter_type=butt),
            pick(T0 + 20_000),
            sighting(T0 + 70_000, 60.180612, 24.83, litter_type=butt),
            pick(T0 + 140_000),
        )
    )
    first, second = sorted(log.spots, key=lambda s: s["lat"])
    assert (first["cleaned_at"], second["cleaned_at"]) == (T0 + 20_000, None)
