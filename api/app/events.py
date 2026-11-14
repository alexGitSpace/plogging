"""Event models from contract/README.md and per-event validation."""

import time
import uuid
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, ValidationError, field_validator

# A `t` outside this window is "clearly wrong" (contract, section Time): the API uses the arrival time instead.
EARLIEST_T = 1767225600000  # 2026-01-01T00:00:00Z
MAX_FUTURE_MS = 24 * 60 * 60 * 1000

LitterType = Literal["bottle", "can", "cigarette_butt", "wrapper", "paper", "other"]
Confidence = Annotated[float, Field(ge=0, le=1)]
Lat = Annotated[float, Field(ge=-90, le=90)]
Lon = Annotated[float, Field(ge=-180, le=180)]


class _Event(BaseModel):
    # Unknown fields are dropped, so nothing outside the contract gets stored or served.
    model_config = ConfigDict(extra="ignore")

    event_id: str
    device_id: Annotated[str, Field(min_length=1)]
    t: int | None = None

    @field_validator("event_id")
    @classmethod
    def _uuid(cls, v: str) -> str:
        try:
            return str(uuid.UUID(v))
        except ValueError:
            raise ValueError("must be a UUID") from None


class SessionStart(_Event):
    type: Literal["session.start"]
    session_id: str
    user_id: str
    role: Literal["plogger", "scout"]
    device_ids: list[str]


class TrackPoint(BaseModel):
    model_config = ConfigDict(extra="ignore")

    t: int
    lat: Lat
    lon: Lon
    accuracy: Annotated[float, Field(ge=0)]


class TrackPoints(_Event):
    type: Literal["track.points"]
    session_id: str
    points: list[TrackPoint]


class Pick(_Event):
    type: Literal["pick"]
    confidence: Confidence


class Sighting(_Event):
    type: Literal["sighting"]
    lat: Lat
    lon: Lon
    litter_type: LitterType
    confidence: Confidence


class SessionEnd(_Event):
    type: Literal["session.end"]
    session_id: str


Event = Annotated[SessionStart | TrackPoints | Pick | Sighting | SessionEnd, Field(discriminator="type")]
_event_adapter: TypeAdapter[Event] = TypeAdapter(Event)


def _format_error(e: ValidationError) -> str:
    parts = []
    for err in e.errors(include_url=False):
        # Drop the union tag ("pick", "sighting", ...) that pydantic puts into the location.
        loc = [str(x) for x in err["loc"]][1:] if err["type"] != "union_tag_invalid" else []
        msg = err["msg"].removeprefix("Value error, ")
        if err["type"] == "union_tag_invalid":
            msg = f"unknown type {err['input'].get('type')!r}" if isinstance(err["input"], dict) else msg
        elif err["type"] == "union_tag_not_found":
            msg = "type is missing"
        parts.append(f"{'.'.join(loc)}: {msg}" if loc else msg)
    return "; ".join(parts)


def parse_event(raw: Any, now_ms: int | None = None) -> tuple[dict | None, str | None]:
    """Validate one raw event. Returns (event, None) or (None, error)."""
    if not isinstance(raw, dict):
        return None, "an event must be a JSON object"
    try:
        event = _event_adapter.validate_python(raw)
    except ValidationError as e:
        return None, _format_error(e)
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    if event.t is None or not (EARLIEST_T <= event.t <= now_ms + MAX_FUTURE_MS):
        event.t = now_ms
    return event.model_dump(mode="json"), None
