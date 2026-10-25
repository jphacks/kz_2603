from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from ..auth import current_device
from ..config import settings
from ..db import get_db
from ..discomfort import discomfort_index
from ..indoor import resolve_room, room_indoor, sensor_readings_by_room
from ..models import Device, IndoorReading
from ..queries import latest_indoor, latest_outdoor
from ..schemas import (
    IndoorOut, LatestConditions, LatestIndoor, LatestOutdoor, ReadingCreate,
    age_minutes, build_conditions, indoor_from_reading,
)

router = APIRouter(tags=["conditions"])


@router.post("/readings", response_model=IndoorOut, status_code=201)
def create_reading(
    body: ReadingCreate,
    device: Device = Depends(current_device),
    db: Session = Depends(get_db),
) -> IndoorOut:
    """Arduino から室内温度を受け取る。湿度がなければ最新の屋外湿度で代用して不快指数を計算する。"""
    if body.humidity is not None:
        humidity, source = body.humidity, "sensor"
    else:
        outdoor = latest_outdoor(db)
        humidity, source = (outdoor.humidity if outdoor else None), "api"

    reading = IndoorReading(
        device_id=device.id,
        temperature=body.temperature,
        humidity=humidity,
        humidity_source=source,
        discomfort_index=discomfort_index(body.temperature, humidity),
    )
    db.add(reading)
    db.commit()
    db.refresh(reading)
    return indoor_from_reading(reading)


@router.get("/conditions/latest", response_model=LatestConditions)
def get_latest_conditions(
    room_id: int | None = Query(None, description="部屋のID（GET /rooms の id）"),
    classroom: str | None = Query(None, description="部屋の名前（room_id の代わり）"),
    db: Session = Depends(get_db),
) -> LatestConditions:
    """室内・屋外の最新値、その差（室内 − 屋外）、今日の予報、各値の鮮度。

    部屋を指定すると、室内はその部屋の値（センサーのある部屋は実測、ほかはダミー）になる。
    指定しなければセンサーの最新値。
    """
    room = resolve_room(db, room_id, classroom)
    if room is not None:
        indoor = room_indoor(room, sensor_readings_by_room(db))
    else:
        indoor = indoor_from_reading(latest_indoor(db))
    outdoor = latest_outdoor(db)
    base = build_conditions(indoor, outdoor)

    latest_in = None
    if indoor:
        age = age_minutes(indoor.measured_at) if indoor.measured_at else None
        latest_in = LatestIndoor(
            **indoor.model_dump(),
            age_minutes=age,
            stale=age is not None and age > settings.stale_after_minutes,
        )
    latest_out = None
    if outdoor:
        latest_out = LatestOutdoor(
            **base.outdoor.model_dump(),
            age_minutes=age_minutes(outdoor.fetched_at),
            forecast_hourly=outdoor.forecast_hourly,
        )
    return LatestConditions(indoor=latest_in, outdoor=latest_out, diff=base.diff)
