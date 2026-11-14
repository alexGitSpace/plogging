import time
import urllib.request

from arduino.app_utils import App, Bridge

_URL = "https://httpbin.org/get"
_TIMEOUT_S = 10


def _report_long_press(count: int):
    """Report each long press to the web so the logs prove the board is online."""
    start = time.monotonic()
    try:
        with urllib.request.urlopen(_URL, timeout=_TIMEOUT_S) as response:
            outcome = f"OK, HTTP {response.status}"
    except OSError as error:  # URLError, HTTPError and timeouts are all OSErrors
        outcome = f"FAILED, {error}"
    elapsed = time.monotonic() - start
    print(f"long press {count}: GET {_URL} -> {outcome} ({elapsed:.1f} s)", flush=True)


Bridge.provide("long_press", _report_long_press)

print("Hello from Linux! Waiting for long presses.", flush=True)
App.run()
