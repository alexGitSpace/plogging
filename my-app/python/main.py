import queue
import threading
import time
import urllib.request
from pathlib import Path

from arduino.app_bricks.image_classification import ImageClassification
from arduino.app_peripherals.camera import Camera
from arduino.app_utils import App, Bridge
from arduino.app_utils.image import compress_to_jpeg

_URL = "https://httpbin.org/get"
_DEADLINE_S = 5  # a long press not fully reported this long after it arrived is dropped
_FLUSH_READS = 5  # reads per photo; the first ones return frames buffered earlier
_MIN_CONFIDENCE = 0.05  # labels below this share (0..1) are not worth printing
_TOP_LABELS = 3
_PHOTO_DIR = Path(__file__).resolve().parent.parent / "photos"  # <app folder>/photos

_camera = Camera(resolution=(640, 480))
_classifier = ImageClassification()
_presses: queue.Queue[tuple[int, float]] = queue.Queue()  # (press number, arrival time)


def _log(message: str):
    """Log with the wall-clock time so lines can be matched to presses and photo names."""
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"{stamp} {message}", flush=True)


def _fetch_outcome(timeout: float) -> str:
    """Fetch the URL and describe how it went, as the log needs words for success and failure alike."""
    try:
        with urllib.request.urlopen(_URL, timeout=timeout) as response:
            return f"OK, HTTP {response.status}"
    except OSError as error:  # URLError, HTTPError and timeouts are all OSErrors
        return f"FAILED, {error}"


def _report_web_request(count: int, timeout: float):
    """Report a web request per long press so the logs prove the board is online."""
    start = time.monotonic()
    outcomes: queue.Queue[str] = queue.Queue()
    # urlopen's own timeout is per network step and can add up, so wait on a thread instead.
    threading.Thread(target=lambda: outcomes.put(_fetch_outcome(timeout)), daemon=True).start()
    try:
        outcome = outcomes.get(timeout=timeout)
    except queue.Empty:
        outcome = "FAILED, no answer before the deadline"
    elapsed = time.monotonic() - start
    _log(f"long press {count}: GET {_URL} -> {outcome} ({elapsed:.1f} s)")


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
    _log(f"long press {count}: camera sees -> {outcome} ({elapsed:.1f} s) [{photo}]")


def _queue_long_press(count: int):
    """Queue each long press with its arrival time, so a late one can be told apart and dropped."""
    arrived = time.monotonic()
    _presses.put((count, arrived))


def _report_next_long_press():
    """Report the next queued long press, dropping whatever cannot start before its deadline."""
    try:
        count, arrived = _presses.get(timeout=1)
    except queue.Empty:
        return
    deadline = arrived + _DEADLINE_S
    if time.monotonic() >= deadline:
        _log(f"long press {count}: DROPPED, waited more than {_DEADLINE_S} s for the previous one")
        return
    _report_classification(count)  # photo first, so it shows the scene at the press
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        _log(f"long press {count}: web request DROPPED, the {_DEADLINE_S} s were used up")
        return
    _report_web_request(count, timeout=remaining)


Bridge.provide("long_press", _queue_long_press)

_camera.start()
_log("Hello from Linux! Waiting for long presses.")
App.run(user_loop=_report_next_long_press)
