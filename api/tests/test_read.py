import json
import time
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import create_app

EXAMPLES = Path(__file__).resolve().parents[2] / "contract" / "examples"
SESSION_ID = "f38b2ffc-80a4-4f5a-91c9-bc701e7ea419"  # in outing.json
T0 = 1794654000000  # session.start in outing.json


def example(name: str) -> dict:
    return json.loads((EXAMPLES / name).read_text())


@pytest.fixture
def client(tmp_path):
    return TestClient(create_app(str(tmp_path / "events.db")))


@pytest.mark.parametrize(
    ("path", "name"),
    [
        ("/api/spots", "spots.json"),
        ("/api/outings", "outings.json"),
        (f"/api/outings/{SESSION_ID}", "outing.json"),
        ("/api/leaderboard", "leaderboard.json"),
    ],
)
def test_responses_match_the_contract_examples(client, path, name):
    client.post("/api/events", json=example("outing.json"))
    assert client.get(path).json() == example(f"responses/{name}")


def test_late_pick_lands_on_the_route(client):
    events = example("outing.json")["events"]
    client.post("/api/events", json={"events": [e for e in events if e["type"] != "pick"]})
    assert client.get(f"/api/outings/{SESSION_ID}").json()["picks"] == []
    # The grabber comes back to Wi-Fi after the outing and sends its pick.
    client.post("/api/events", json={"events": [e for e in events if e["type"] == "pick"]})
    assert client.get(f"/api/outings/{SESSION_ID}").json() == example("responses/outing.json")


def test_filters(client):
    client.post("/api/events", json=example("outing.json"))
    assert client.get("/api/outings", params={"user_id": "user-1"}).json() == example("responses/outings.json")
    assert client.get("/api/outings", params={"user_id": "someone-else"}).json() == {"outings": []}
    assert client.get("/api/outings", params={"since": T0 + 1}).json() == {"outings": []}
    assert client.get("/api/leaderboard", params={"since": T0 + 1}).json() == {"users": []}


def test_unknown_outing(client):
    assert client.get("/api/outings/nope").status_code == 404


def test_live_outing(client):
    now = int(time.time() * 1000)
    start = {
        "event_id": str(uuid.uuid4()),
        "type": "session.start",
        "device_id": "phone-1",
        "t": now - 5_000,
        "session_id": "live",
        "user_id": "user-1",
        "role": "plogger",
        "device_ids": ["grabber-1"],
    }
    pick = {"event_id": str(uuid.uuid4()), "type": "pick", "device_id": "grabber-1", "t": now, "confidence": 0.9}
    client.post("/api/events", json={"events": [start, pick]})
    outing = client.get("/api/outings/live").json()
    assert (outing["live"], outing["ended_at"], outing["stats"]["picks"]) == (True, None, 1)
    assert outing["stats"]["duration_s"] >= 5
