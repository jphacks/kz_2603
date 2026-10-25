from datetime import datetime, time, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..auth import current_user
from ..config import settings
from ..db import get_db
from ..indoor import room_indoor, sensor_readings_by_room
from ..models import IndoorReading, Room, RoomPost, User
from ..schemas import (
    FEELING_LABELS, FeelingCounts, RoomOut, RoomPostCreate, RoomPostOut, age_minutes,
)

router = APIRouter(tags=["plaza"])

# 日本は夏時間がないので固定オフセットで十分（slim イメージに tzdata がなくても動く）
JST = timezone(timedelta(hours=9))


def _today_start() -> datetime:
    """日本時間の今日0時（UTC で返す）。"""
    today = datetime.now(JST).date()
    return datetime.combine(today, time.min, tzinfo=JST).astimezone(timezone.utc)


def _get_room(db: Session, room_id: int) -> Room:
    room = db.get(Room, room_id)
    if room is None:
        raise HTTPException(404, "room not found")
    return room


def _today_counts(db: Session) -> dict[int, FeelingCounts]:
    rows = db.execute(
        select(RoomPost.room_id, RoomPost.feeling, func.count())
        .where(RoomPost.created_at >= _today_start())
        .group_by(RoomPost.room_id, RoomPost.feeling)
    )
    counts: dict[int, FeelingCounts] = {}
    for room_id, feeling, n in rows:
        c = counts.setdefault(room_id, FeelingCounts())
        setattr(c, feeling, n)
        c.total += n
    return counts


def _room_out(
    room: Room, counts: dict[int, FeelingCounts], readings: dict[str, IndoorReading]
) -> RoomOut:
    """センサーと同じ名前の部屋は室温をセンサーの最新値にし、それ以外はダミー値を返す。"""
    indoor = room_indoor(room, readings)
    stale = (
        indoor.measured_at is not None
        and age_minutes(indoor.measured_at) > settings.stale_after_minutes
    )
    return RoomOut(
        id=room.id, name=room.name, temperature=indoor.temperature, humidity=indoor.humidity,
        is_dummy=indoor.temperature_source == "dummy",
        temperature_source=indoor.temperature_source, humidity_source=indoor.humidity_source,
        measured_at=indoor.measured_at, stale=stale, today=counts.get(room.id, FeelingCounts()),
    )


def _post_out(p: RoomPost) -> RoomPostOut:
    return RoomPostOut(
        id=p.id, room_id=p.room_id, feeling=p.feeling,
        feeling_label=FEELING_LABELS[p.feeling], comment=p.comment, created_at=p.created_at,
    )


@router.get("/rooms", response_model=list[RoomOut])
def list_rooms(db: Session = Depends(get_db)) -> list[RoomOut]:
    """場所の一覧。室温・湿度（センサーのある部屋は実測、ほかはダミー）と、今日の体感ごとの投稿数。"""
    counts, readings = _today_counts(db), sensor_readings_by_room(db)
    return [_room_out(r, counts, readings) for r in db.scalars(select(Room).order_by(Room.id))]


@router.get("/rooms/{room_id}", response_model=RoomOut)
def get_room(room_id: int, db: Session = Depends(get_db)) -> RoomOut:
    return _room_out(_get_room(db, room_id), _today_counts(db), sensor_readings_by_room(db))


@router.get("/rooms/{room_id}/posts", response_model=list[RoomPostOut])
def list_room_posts(
    room_id: int,
    all: bool = Query(False, description="true なら今日より前の投稿も返す"),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    db: Session = Depends(get_db),
) -> list[RoomPostOut]:
    """その場所の投稿を新しい順に返す。初期値は今日（日本時間0時から）の分だけ。"""
    _get_room(db, room_id)
    stmt = select(RoomPost).where(RoomPost.room_id == room_id)
    if not all:
        stmt = stmt.where(RoomPost.created_at >= _today_start())
    stmt = stmt.order_by(RoomPost.created_at.desc(), RoomPost.id.desc()).limit(limit).offset(offset)
    return [_post_out(p) for p in db.scalars(stmt)]


@router.post("/rooms/{room_id}/posts", response_model=RoomPostOut, status_code=201)
def create_room_post(
    room_id: int,
    body: RoomPostCreate,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> RoomPostOut:
    """体感を投稿する。feeling は英語コードでも画面の日本語（快適など）でもよい。"""
    _get_room(db, room_id)
    post = RoomPost(
        room_id=room_id, user_id=user.id,
        feeling=body.normalized_feeling(), comment=body.normalized_comment(),
    )
    db.add(post)
    db.commit()
    db.refresh(post)
    return _post_out(post)
