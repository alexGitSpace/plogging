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


def test_page(client):
    res = client.get("/")
    assert res.status_code == 200
    assert "api/events" in res.text
