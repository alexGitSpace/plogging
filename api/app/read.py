"""What the app and the glasses read: outings, litter spots and the leaderboard.

Points, quests, streaks and "you are #5" are left to the app: it derives them from these.
"""

import time
from collections import defaultdict
from typing import Annotated

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from app.outings import Log, Outing, build, is_live, stats
from app.store import Store

Since = Annotated[int | None, Query(description="Only outings started at or after this Unix time in ms")]


class Stats(BaseModel):
    duration_s: int
    distance_m: int
    picks: int
    spots_cleaned: int
    spots_pinned: int
    litter: dict[str, int]  # Picks by litter type, for the picks that cleaned a spot


class OutingSummary(BaseModel):
    session_id: str
    user_id: str | None
    role: str | None
    started_at: int
    ended_at: int | None
    live: bool
    stats: Stats


class PickOnRoute(BaseModel):
    t: int
    lat: float | None
    lon: float | None
    litter_type: str | None
    spot_id: str | None


class Spot(BaseModel):
    spot_id: str
    lat: float
    lon: float
    litter_type: str
    spotted_at: int
    spotted_by: str | None
    cleaned_at: int | None
    cleaned_by: str | None


class OutingCard(OutingSummary):
    route: list[tuple[float, float]]  # [lat, lon] in time order
    picks: list[PickOnRoute]
    pinned: list[Spot]


class OutingList(BaseModel):
    outings: list[OutingSummary]


class SpotList(BaseModel):
    spots: list[Spot]


class LeaderRow(BaseModel):
    rank: int
    user_id: str
    outings: int
    stats: Stats


class Leaderboard(BaseModel):
    users: list[LeaderRow]


def _summary(o: Outing, now_ms: int) -> dict:
    return {
        "session_id": o.session_id,
        "user_id": o.user_id,
        "role": o.role,
        "started_at": o.started_at,
        "ended_at": o.ended_at,
        "live": is_live(o, now_ms),
        "stats": stats([o], now_ms),
    }


def _since(log: Log, since: int | None) -> list[Outing]:
    return [o for o in log.outings if since is None or o.started_at >= since]


def read_router(store: Store) -> APIRouter:
    router = APIRouter()
    cache: dict = {"seq": None, "log": None}

    def current() -> Log:
        """Built again only after new events arrive: the live screen and the glasses poll every second or two.
        live and duration_s depend on the time of the request, so they are computed per response, not here."""
        seq = store.last_seq()
        if cache["seq"] != seq:
            cache["log"], cache["seq"] = build(store.all()), seq
        return cache["log"]

    @router.get("/api/outings")
    def get_outings(
        user_id: str | None = None,
        since: Since = None,
        limit: Annotated[int, Query(ge=1, le=500)] = 50,
    ) -> OutingList:
        """Outings, newest first."""
        now_ms = int(time.time() * 1000)
        outings = [o for o in _since(current(), since) if user_id is None or o.user_id == user_id]
        return OutingList(outings=[_summary(o, now_ms) for o in outings[:limit]])

    @router.get("/api/outings/{session_id}")
    def get_outing(session_id: str) -> OutingCard:
        """One outing with its route, the picks on it and the spots its glasses pinned. Poll it for the live screen."""
        now_ms = int(time.time() * 1000)
        o = next((o for o in current().outings if o.session_id == session_id), None)
        if o is None:
            raise HTTPException(404, "No such outing")
        return OutingCard(
            **_summary(o, now_ms),
            route=[(p["lat"], p["lon"]) for p in o.route],
            picks=o.picks,
            pinned=o.pinned,
        )

    @router.get("/api/spots")
    def get_spots() -> SpotList:
        """Litter the glasses saw, newest first. A spot stays open until a pick within 30 m cleans it."""
        return SpotList(spots=current().spots)

    @router.get("/api/leaderboard")
    def get_leaderboard(since: Since = None) -> Leaderboard:
        """Users by picks, then by distance. For "this week" pass the start of the week as since."""
        now_ms = int(time.time() * 1000)
        by_user: dict[str, list[Outing]] = defaultdict(list)
        for o in _since(current(), since):
            if o.user_id is not None:
                by_user[o.user_id].append(o)
        rows = [{"user_id": u, "outings": len(os), "stats": stats(os, now_ms)} for u, os in by_user.items()]
        rows.sort(key=lambda r: (-r["stats"]["picks"], -r["stats"]["distance_m"], r["user_id"]))
        return Leaderboard(users=[LeaderRow(rank=i, **r) for i, r in enumerate(rows, start=1)])

    return router
