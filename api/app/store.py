"""Events in one SQLite file. The raw event JSON is the source of truth; the columns are for queries."""

import json
import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT NOT NULL UNIQUE,
    type TEXT NOT NULL,
    device_id TEXT NOT NULL,
    t INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS events_type_t ON events (type, t);
"""


class Store:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        db = self._connect()
        try:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)
        finally:
            db.close()

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path, timeout=10)

    def add(self, events: list[dict], received_at: int) -> None:
        """Insert events; an event_id that is already stored is ignored (devices resend)."""
        rows = [(e["event_id"], e["type"], e["device_id"], e["t"], received_at, json.dumps(e)) for e in events]
        db = self._connect()
        try:
            with db:
                db.executemany(
                    "INSERT OR IGNORE INTO events (event_id, type, device_id, t, received_at, body) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    rows,
                )
        finally:
            db.close()

    def latest(self, limit: int) -> list[dict]:
        """The latest events by arrival, newest first."""
        db = self._connect()
        try:
            rows = db.execute("SELECT body FROM events ORDER BY seq DESC LIMIT ?", (limit,)).fetchall()
        finally:
            db.close()
        return [json.loads(body) for (body,) in rows]
