import json
import time
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import create_app

EXAMPLES = Path(__file__).resolve().parents[2] / "contract" / "examples"


@pytest.fixture
def client(tmp_path):
    return TestClient(create_app(str(tmp_path / "events.db")))


def example(name: str) -> dict:
    return json.loads((EXAMPLES / name).read_text())


def pick(**overrides) -> dict:
    return {
        "event_id": str(uuid.uuid4()),
        "type": "pick",
        "device_id": "grabber-1",
        "t": int(time.time() * 1000),
        "confidence": 0.9,
    } | overrides


@pytest.mark.parametrize("name", ["outing.json", "pick.json", "sighting.json"])
def test_contract_examples_are_accepted(client, name):
    body = example(name)
    res = client.post("/api/events", json=body)
    assert res.status_code == 200
    assert res.json() == {"accepted": [e["event_id"] for e in body["events"]], "rejected": []}


def test_outing_covers_every_type(client):
    types = {e["type"] for e in example("outing.json")["events"]}
    assert types == {"session.start", "track.points", "pick", "sighting", "session.end"}


def test_latest_events_newest_first_and_as_sent(client):
    body = example("outing.json")
    client.post("/api/events", json=body)
    events = client.get("/api/events", params={"limit": 100}).json()["events"]
    assert events == list(reversed(body["events"]))
    assert len(client.get("/api/events", params={"limit": 2}).json()["events"]) == 2


def test_resent_event_is_accepted_once_stored(client):
    e = pick()
    for _ in range(2):
        assert client.post("/api/events", json={"events": [e]}).json()["accepted"] == [e["event_id"]]
    assert len(client.get("/api/events").json()["events"]) == 1


def test_bad_event_is_rejected_alone(client):
    good, bad = pick(), pick(confidence=1.5)
    res = client.post("/api/events", json={"events": [good, bad, {"event_id": "x", "type": "nope"}, 7]}).json()
    assert res["accepted"] == [good["event_id"]]
    assert [r["event_id"] for r in res["rejected"]] == [bad["event_id"], "x", None]
    assert "confidence" in res["rejected"][0]["error"]
    assert "unknown type 'nope'" in res["rejected"][1]["error"]


def test_missing_or_wrong_t_becomes_arrival_time(client):
    no_t = pick()
    del no_t["t"]
    seconds = pick(t=int(time.time()))  # seconds instead of milliseconds
    before = int(time.time() * 1000)
    client.post("/api/events", json={"events": [no_t, seconds]})
    for e in client.get("/api/events").json()["events"]:
        assert e["t"] >= before


def test_fields_outside_the_contract_are_dropped(client):
    e = pick(secret="x")
    client.post("/api/events", json={"events": [e]})
    assert "secret" not in client.get("/api/events").json()["events"][0]


def test_more_than_100_events_is_refused(client):
    res = client.post("/api/events", json={"events": [pick() for _ in range(101)]})
    assert res.status_code == 422


def test_body_over_1_mb_is_refused(client):
    body = json.dumps({"events": [pick(padding="x" * 1_000_000)]}).encode()
    headers = {"content-type": "application/json"}
    assert client.post("/api/events", content=body, headers=headers).status_code == 413
    # Sent in chunks, without Content-Length: the API counts the bytes as they arrive.
    assert client.post("/api/events", content=iter([body]), headers=headers).status_code == 413
    assert client.get("/api/events").json()["events"] == []


def test_hour_of_track_fits_one_request(client):
    start = 1794654000000
    points = [{"t": start + i * 1000, "lat": 60.1841, "lon": 24.8301, "accuracy": 4.7} for i in range(3600)]
    track = {**example("outing.json")["events"][2], "points": points}
    res = client.post("/api/events", json={"events": [track]})
    assert res.status_code == 200
    assert res.json()["accepted"] == [track["event_id"]]


def test_ids_over_100_characters_are_rejected(client):
    start = example("outing.json")["events"][0]
    res = client.post(
        "/api/events",
        json={"events": [pick(device_id="x" * 101), start | {"user_id": "x" * 101}, start | {"device_ids": [""]}]},
    ).json()
    assert [r["error"] for r in res["rejected"]] == [
        "device_id: String should have at most 100 characters",
        "user_id: String should have at most 100 characters",
        "device_ids.0: String should have at least 1 character",
    ]


def test_page(client):
    res = client.get("/")
    assert res.status_code == 200
    assert "api/events" in res.text
