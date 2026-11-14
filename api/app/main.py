"""Plogging API: events in, outings and spots out, a page of the latest events and a page that records an outing."""

import os
import time
from pathlib import Path
from typing import Annotated, Any

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field
from starlette.datastructures import Headers
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.events import parse_event
from app.read import read_router
from app.recognise import (
    ClassifyFn,
    GeminiError,
    ImageError,
    MissingKeyError,
    RecogniseRequest,
    RecogniseResult,
    decode_jpeg,
    gemini_classify,
)
from app.store import Store

MAX_BATCH = 100
MAX_BODY_BYTES = 1_000_000  # An hour of track at one point per second takes about a quarter of it
PAGE = Path(__file__).with_name("page.html")
RECORD_PAGE = Path(__file__).with_name("record.html")


class BodySizeLimit:
    """Answer 413 to a request body over max_bytes, whether or not the client sends Content-Length."""

    def __init__(self, app: ASGIApp, max_bytes: int) -> None:
        self.app = app
        self.max_bytes = max_bytes
        self.detail = f"The request body is over {max_bytes} bytes: send fewer events per request"

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        length = Headers(scope=scope).get("content-length", "")
        if length.isdigit() and int(length) > self.max_bytes:
            await JSONResponse({"detail": self.detail}, status_code=413)(scope, receive, send)
            return
        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            received += len(message.get("body", b""))
            if received > self.max_bytes:
                # FastAPI passes an HTTPException raised while it reads the body on to its handler.
                raise HTTPException(413, self.detail)
            return message

        await self.app(scope, limited_receive, send)


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


def create_app(db_path: str, classify: ClassifyFn | None = None) -> FastAPI:
    store = Store(db_path)
    run_classify = classify if classify is not None else gemini_classify
    app = FastAPI(title="plogging API", summary="Events format: contract/README.md in the repository")
    # Added before CORS so that CORS wraps it and browsers can read the 413.
    app.add_middleware(BodySizeLimit, max_bytes=MAX_BODY_BYTES)
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

    app.include_router(read_router(store))

    @app.post("/api/recognise")
    def post_recognise(body: RecogniseRequest) -> RecogniseResult:
        try:
            image = decode_jpeg(body.image_jpeg_base64)
        except ImageError as e:
            raise HTTPException(status_code=422, detail=str(e)) from e
        try:
            return run_classify(image, body.hint, body.hint_confidence)
        except MissingKeyError as e:
            raise HTTPException(status_code=503, detail=str(e)) from e
        except GeminiError as e:
            raise HTTPException(status_code=502, detail=str(e)) from e

    @app.get("/", response_class=HTMLResponse, include_in_schema=False)
    def page() -> str:
        return PAGE.read_text()

    # The phone records an outing here. Served by the API to get its HTTPS (the location needs it) and origin.
    @app.get("/record", response_class=HTMLResponse, include_in_schema=False)
    def record_page() -> str:
        return RECORD_PAGE.read_text()

    return app


app = create_app(os.environ.get("DATABASE_PATH", "data/events.db"))
