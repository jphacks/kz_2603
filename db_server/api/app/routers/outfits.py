import json
from typing import Any

from fastapi import APIRouter, Body, Depends, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..auth import current_user, require_self
from ..db import get_db
from ..indoor import default_room, resolve_room, room_indoor, sensor_readings_by_room
from ..models import Device, Garment, IndoorReading, Outfit, Room, User
from ..queries import latest_indoor, latest_outdoor
from ..schemas import (
    FeedbackUpdate, GarmentOut, GarmentWithContext, OutfitOut, Part, Verdict,
    garment_out, garment_with_context, outfit_out,
)
from ..storage import image_response, save_image

router = APIRouter()


def _own_outfit(db: Session, outfit_id: int, user: User) -> Outfit:
    outfit = db.get(Outfit, outfit_id)
    if outfit is None:
        raise HTTPException(404, "outfit not found")
    require_self(outfit.user_id, user)
    return outfit


def _own_garment(db: Session, garment_id: int, user: User) -> Garment:
    garment = db.get(Garment, garment_id)
    if garment is None:
        raise HTTPException(404, "garment not found")
    require_self(garment.user_id, user)
    return garment


def _json_form(value: str | None, name: str) -> dict[str, Any] | None:
    """multipart で文字列として届いた JSON をオブジェクトに戻す。"""
    if value is None or value == "":
        return None
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        raise HTTPException(422, f"{name} is not valid JSON")
    if not isinstance(parsed, dict):
        raise HTTPException(422, f"{name} must be a JSON object")
    return parsed


# --- ① コーデ写真 + 判定 -------------------------------------------------

@router.post("/outfits", response_model=OutfitOut, status_code=201, tags=["outfits"])
def create_outfit(
    image: UploadFile = File(description="コーデ写真 (jpeg/png/webp)"),
    verdict: Verdict = Form(),
    comment: str | None = Form(default=None),
    analysis: str | None = Form(default=None, description="AIの分析結果（JSON文字列、任意）"),
    room_id: int | None = Form(default=None, description="判定した部屋のID（GET /rooms の id）"),
    classroom: str | None = Form(default=None, description="判定した部屋の名前（room_id の代わり）"),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> OutfitOut:
    """写真と判定結果を保存し、判定した部屋の室内の値と最新の屋外データを記録する。

    部屋の室内の値は、センサーのある部屋なら実測、ほかの部屋ならダミー値。
    部屋を指定しなければセンサーの部屋（DEVICE_ROOM_NAME）として扱う。
    """
    analysis_obj = _json_form(analysis, "analysis")
    room = resolve_room(db, room_id, classroom) or default_room(db)
    outdoor = latest_outdoor(db)
    outfit = Outfit(
        user_id=user.id,
        image_path=save_image(image, "outfits"),
        verdict=verdict,
        comment=comment,
        analysis=analysis_obj,
        outdoor_weather_id=outdoor.id if outdoor else None,
    )
    if room is not None:
        indoor = room_indoor(room, sensor_readings_by_room(db))
        outfit.room_id = room.id
        outfit.indoor_reading_id = indoor.id
        outfit.indoor_temperature = indoor.temperature
        outfit.indoor_humidity = indoor.humidity
        outfit.indoor_temperature_source = indoor.temperature_source
        outfit.indoor_humidity_source = indoor.humidity_source
        outfit.indoor_discomfort_index = indoor.discomfort_index
    else:
        # 部屋が1つも登録されていないとき（通常は起こらない）はセンサー値だけ紐付ける
        reading = latest_indoor(db)
        outfit.indoor_reading_id = reading.id if reading else None
    db.add(outfit)
    db.commit()
    db.refresh(outfit)
    return outfit_out(outfit)


@router.get("/outfits/{outfit_id}", response_model=OutfitOut, tags=["outfits"])
def get_outfit(
    outfit_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)
) -> OutfitOut:
    return outfit_out(_own_outfit(db, outfit_id, user))


@router.get("/outfits/{outfit_id}/image", response_class=FileResponse, tags=["outfits"])
def get_outfit_image(
    outfit_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)
) -> FileResponse:
    return image_response(_own_outfit(db, outfit_id, user).image_path)


@router.patch("/outfits/{outfit_id}/feedback", response_model=OutfitOut, tags=["outfits"])
def update_feedback(
    outfit_id: int,
    body: FeedbackUpdate,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> OutfitOut:
    """感想を記録する。英語 (hot/cold/just_right) と日本語 (暑かった/寒かった/ちょうどいい) のどちらでも受け付け、英語で保存する。"""
    outfit = _own_outfit(db, outfit_id, user)
    outfit.user_feedback = body.normalized()
    db.commit()
    return outfit_out(outfit)


@router.get("/users/{user_id}/outfits", response_model=list[OutfitOut], tags=["outfits"])
def list_outfits(
    user_id: int,
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    classroom: str | None = Query(None, description="判定した部屋の名前で絞る"),
    temp_min: float | None = Query(None, description="判定時の室温の下限"),
    temp_max: float | None = Query(None, description="判定時の室温の上限"),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> list[OutfitOut]:
    """コーデ履歴を新しい順に返す。条件を付けると、判定時の室温・部屋で絞り込む。"""
    require_self(user_id, user)
    stmt = select(Outfit).where(Outfit.user_id == user_id)
    if classroom is not None or temp_min is not None or temp_max is not None:
        # 部屋の記録がない古いコーデは、紐付いたセンサー値で判定する
        stmt = (
            stmt.outerjoin(IndoorReading, Outfit.indoor_reading_id == IndoorReading.id)
            .outerjoin(Device, IndoorReading.device_id == Device.id)
            .outerjoin(Room, Outfit.room_id == Room.id)
        )
        temperature = func.coalesce(Outfit.indoor_temperature, IndoorReading.temperature)
        room_name = func.coalesce(Room.name, Device.room_name)
        if temp_min is not None:
            stmt = stmt.where(temperature >= temp_min)
        if temp_max is not None:
            stmt = stmt.where(temperature <= temp_max)
        if classroom is not None:
            stmt = stmt.where(room_name == classroom)
    stmt = stmt.order_by(Outfit.taken_at.desc(), Outfit.id.desc()).limit(limit).offset(offset)
    return [outfit_out(o) for o in db.scalars(stmt)]


# --- ② 上下に分けた服 ---------------------------------------------------

@router.post(
    "/outfits/{outfit_id}/garments", response_model=GarmentOut, status_code=201, tags=["garments"]
)
def create_garment(
    outfit_id: int,
    image: UploadFile = File(description="切り出した服の画像"),
    part: Part = Form(),
    meta: str | None = Form(default=None, description="服の情報（JSON文字列、任意）"),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> GarmentOut:
    meta_obj = _json_form(meta, "meta")
    outfit = _own_outfit(db, outfit_id, user)
    garment = Garment(
        user_id=user.id, outfit_id=outfit.id, part=part,
        image_path=save_image(image, "garments"), meta=meta_obj,
    )
    db.add(garment)
    db.commit()
    db.refresh(garment)
    return garment_out(garment)


@router.get(
    "/users/{user_id}/garments", response_model=list[GarmentWithContext], tags=["garments"]
)
def list_garments(
    user_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)
) -> list[GarmentWithContext]:
    """ユーザーの服を全件返す。絞り込みはせず、選ぶのはAI側。"""
    require_self(user_id, user)
    garments = db.scalars(
        select(Garment).where(Garment.user_id == user_id).order_by(Garment.created_at.desc())
    )
    return [garment_with_context(g) for g in garments]


@router.patch("/garments/{garment_id}/meta", response_model=GarmentOut, tags=["garments"])
def update_garment_meta(
    garment_id: int,
    body: dict[str, Any] = Body(examples=[{"hidden": True}]),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> GarmentOut:
    """meta を部分更新する。渡したキーだけ上書きし、ほかのキーは残す。"""
    garment = _own_garment(db, garment_id, user)
    garment.meta = {**(garment.meta or {}), **body}
    db.commit()
    return garment_out(garment)


@router.get("/garments/{garment_id}/image", response_class=FileResponse, tags=["garments"])
def get_garment_image(
    garment_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)
) -> FileResponse:
    return image_response(_own_garment(db, garment_id, user).image_path)
