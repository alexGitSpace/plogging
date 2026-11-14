"""Plogging API stub: POST /api/events, GET /api/events and a page with the latest events."""

import os
import time
from pathlib import Path
from typing import Annotated, Any

from fastapi import FastAPI, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

from app.events import parse_event
from app.store import Store

MAX_BATCH = 100
PAGE = Path(__file__).with_name("page.html")


class EventBatch(BaseModel):
    # Items stay raw here so that one bad event is rejected alone instead of failing the whole request.
    events: Annotated[list[Any], Field(max_length=MAX_BATCH)]


class Rejected(BaseModel):
    event_id: Any = None
    error: str


class PostResult(BaseModel):
    accepted: list[str]
    rejected: list[Rejected]


class EventList(BaseModel):
    events: list[dict[str, Any]]


def create_app(db_path: str) -> FastAPI:
    store = Store(db_path)
    app = FastAPI(title="plogging API", summary="Events format: contract/README.md in the repository")
    # Devices, the web app and the glasses web app may live on other origins. Nothing here needs cookies.
    app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["GET", "POST"], allow_headers=["*"])

    @app.post("/api/events")
    def post_events(batch: EventBatch) -> PostResult:
        now_ms = int(time.time() * 1000)
        accepted, rejected, valid = [], [], []
        for raw in batch.events:
            event, error = parse_event(raw, now_ms)
            if event is None:
                event_id = raw.get("event_id") if isinstance(raw, dict) else None
                rejected.append(Rejected(event_id=event_id, error=error))
            else:
                valid.append(event)
                accepted.append(event["event_id"])
        store.add(valid, received_at=now_ms)
        return PostResult(accepted=accepted, rejected=rejected)

    @app.get("/api/events")
    def get_events(limit: Annotated[int, Query(ge=1, le=1000)] = 20) -> EventList:
        return EventList(events=store.latest(limit))

    @app.get("/", response_class=HTMLResponse, include_in_schema=False)
    def page() -> str:
        return PAGE.read_text()

    return app


app = create_app(os.environ.get("DATABASE_PATH", "data/events.db"))
