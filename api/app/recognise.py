"""POST /api/recognise: one JPEG crop, a contract litter type from Gemini."""

from __future__ import annotations

import base64
import json
import math
import os
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Any

from pydantic import BaseModel

from app.events import Confidence, LitterType

MAX_IMAGE_BYTES = 1_048_576
JPEG_MAGIC = b"\xff\xd8\xff"
DEFAULT_MODEL = "gemini-3.5-flash-lite"
GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
TIMEOUT_S = 8
LITTER_TYPES = ("bottle", "can", "cigarette_butt", "wrapper", "paper", "other")
PROMPT = (
    "This crop is from Meta glasses at the moment of a pick. "
    "The on-device guess may be wrong. Name the litter in the hand. "
    "If it is not litter, return other with a low confidence."
)
RESPONSE_SCHEMA = {
    "type": "object",
    "properties": {
        "litter_type": {"type": "string", "enum": list(LITTER_TYPES)},
        "confidence": {"type": "number"},
    },
    "required": ["litter_type", "confidence"],
}


class RecogniseRequest(BaseModel):
    image_jpeg_base64: str
    hint: str | None = None
    hint_confidence: Confidence | None = None


class RecogniseResult(BaseModel):
    litter_type: LitterType
    confidence: Confidence


class ImageError(ValueError):
    pass


class MissingKeyError(RuntimeError):
    pass


class GeminiError(RuntimeError):
    pass


ClassifyFn = Callable[[bytes, str | None, float | None], RecogniseResult]


def decode_jpeg(raw_b64: str) -> bytes:
    try:
        data = base64.b64decode(raw_b64.strip(), validate=True)
    except (ValueError, TypeError) as e:
        raise ImageError("image_jpeg_base64 must be base64") from e
    if len(data) > MAX_IMAGE_BYTES:
        raise ImageError("image is larger than 1 MB")
    if not data.startswith(JPEG_MAGIC):
        raise ImageError("image is not a JPEG")
    return data


def parse_gemini(payload: dict[str, Any]) -> RecogniseResult:
    try:
        text = payload["candidates"][0]["content"]["parts"][0]["text"]
        data = json.loads(text)
        litter_type = data["litter_type"]
        confidence = float(data["confidence"])
    except (KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as e:
        raise GeminiError("Gemini response was unusable") from e
    if litter_type not in LITTER_TYPES or not math.isfinite(confidence):
        raise GeminiError("Gemini response was unusable")
    return RecogniseResult(litter_type=litter_type, confidence=min(1.0, max(0.0, confidence)))


def gemini_classify(image: bytes, hint: str | None, hint_confidence: float | None) -> RecogniseResult:
    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        raise MissingKeyError("GEMINI_API_KEY is not set")
    model = os.environ.get("GEMINI_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL
    prompt = PROMPT
    if hint:
        prompt += f" On-device guess: {hint}"
        if hint_confidence is not None:
            prompt += f" ({hint_confidence:.2f})"
        prompt += "."
    body = {
        "contents": [
            {
                "parts": [
                    {"text": prompt},
                    {
                        "inline_data": {
                            "mime_type": "image/jpeg",
                            "data": base64.b64encode(image).decode("ascii"),
                        }
                    },
                ]
            }
        ],
        "generationConfig": {
            "temperature": 0,
            "responseMimeType": "application/json",
            "responseSchema": RESPONSE_SCHEMA,
        },
    }
    url = f"{GEMINI_URL.format(model=model)}?key={key}"
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise GeminiError(f"Gemini HTTP {e.code}") from None
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError):
        raise GeminiError("Gemini request failed") from None
    if not isinstance(payload, dict):
        raise GeminiError("Gemini response was unusable")
    return parse_gemini(payload)
