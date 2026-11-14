import time
import urllib.request
from pathlib import Path

from arduino.app_bricks.image_classification import ImageClassification
from arduino.app_peripherals.camera import Camera
from arduino.app_utils import App, Bridge
from arduino.app_utils.image import compress_to_jpeg

_URL = "https://httpbin.org/get"
_TIMEOUT_S = 10
_FLUSH_READS = 5  # reads per photo; the first ones return frames buffered earlier
_MIN_CONFIDENCE = 0.05  # labels below this share (0..1) are not worth printing
_TOP_LABELS = 3
_PHOTO_DIR = Path(__file__).resolve().parent.parent / "photos"  # <app folder>/photos

_camera = Camera(resolution=(640, 480))
_classifier = ImageClassification()


def _report_web_request(count: int):
    """Report a web request per long press so the logs prove the board is online."""
    start = time.monotonic()
    try:
        with urllib.request.urlopen(_URL, timeout=_TIMEOUT_S) as response:
            outcome = f"OK, HTTP {response.status}"
    except OSError as error:  # URLError, HTTPError and timeouts are all OSErrors
        outcome = f"FAILED, {error}"
    elapsed = time.monotonic() - start
    print(f"long press {count}: GET {_URL} -> {outcome} ({elapsed:.1f} s)", flush=True)


def _capture_fresh_jpeg() -> bytes | None:
    """Capture the scene as it is now; a camera read only on demand returns old frames first."""
    frame = None
    for _ in range(_FLUSH_READS):
        frame = _camera.capture()
    jpeg = None if frame is None else compress_to_jpeg(frame)
    return None if jpeg is None else jpeg.tobytes()


def _save_photo(jpeg: bytes, count: int) -> str:
    """Save each photo so it can be copied off the board and compared with its labels."""
    _PHOTO_DIR.mkdir(exist_ok=True)
    path = _PHOTO_DIR / f"{time.strftime('%Y%m%d-%H%M%S')}-press{count}.jpg"
    path.write_bytes(jpeg)
    return f"{_PHOTO_DIR.name}/{path.name}"


def _report_classification(count: int):
    """Report what the camera sees per long press so the logs show the on-device AI result."""
    start = time.monotonic()
    jpeg = _capture_fresh_jpeg()
    result = _classifier.classify(jpeg, confidence=_MIN_CONFIDENCE) if jpeg else None
    labels = sorted(
        result["classification"] if result else [],
        key=lambda label: float(label["confidence"]),
        reverse=True,
    )[:_TOP_LABELS]
    if jpeg is None:
        outcome = "FAILED, no frame from the camera"
    elif result is None:
        outcome = "FAILED, no answer from the classifier"
    elif not labels:
        outcome = f"nothing above {_MIN_CONFIDENCE * 100:.0f} %"
    else:
        outcome = ", ".join(f"{label['class_name']} {float(label['confidence']):.0f} %" for label in labels)
    elapsed = time.monotonic() - start
    photo = _save_photo(jpeg, count) if jpeg else "no photo"
    print(f"long press {count}: camera sees -> {outcome} ({elapsed:.1f} s) [{photo}]", flush=True)


def _report_long_press(count: int):
    """Report each long press twice: the web request first, as it works without a camera."""
    _report_web_request(count)
    _report_classification(count)


Bridge.provide("long_press", _report_long_press)

_camera.start()
print("Hello from Linux! Waiting for long presses.", flush=True)
App.run()
