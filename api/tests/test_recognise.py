import base64

from fastapi.testclient import TestClient

from app.main import create_app
from app.recognise import RecogniseResult

TINY_JPEG = b"\xff\xd8\xff\xd9"
TINY_JPEG_B64 = base64.b64encode(TINY_JPEG).decode("ascii")


def client(tmp_path, classify=None):
    return TestClient(create_app(str(tmp_path / "events.db"), classify=classify))


def test_mapped_result(tmp_path):
    def classify(image, hint, hint_confidence):
        assert image == TINY_JPEG
        assert hint == "Can"
        assert hint_confidence == 0.41
        return RecogniseResult(litter_type="wrapper", confidence=0.82)

    res = client(tmp_path, classify).post(
        "/api/recognise",
        json={"image_jpeg_base64": TINY_JPEG_B64, "hint": "Can", "hint_confidence": 0.41},
    )
    assert res.status_code == 200
    assert res.json() == {"litter_type": "wrapper", "confidence": 0.82}


def test_bad_image_is_rejected(tmp_path):
    def classify(image, hint, hint_confidence):
        raise AssertionError("classifier must not run")

    res = client(tmp_path, classify).post(
        "/api/recognise", json={"image_jpeg_base64": base64.b64encode(b"hello").decode()}
    )
    assert res.status_code == 422
    assert "JPEG" in res.json()["detail"]


def test_missing_key(tmp_path, monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    res = TestClient(create_app(str(tmp_path / "events.db"))).post(
        "/api/recognise",
        json={"image_jpeg_base64": TINY_JPEG_B64},
    )
    assert res.status_code == 503
    assert "GEMINI_API_KEY" in res.json()["detail"]
