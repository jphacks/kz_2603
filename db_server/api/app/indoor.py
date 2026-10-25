"""部屋ごとの室内の値（センサーのある部屋は実測、ほかはダミー）を求める。"""
from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from .config import settings
from .discomfort import discomfort_index
from .models import Device, IndoorReading, Room
from .schemas import IndoorOut


def sensor_readings_by_room(db: Session) -> dict[str, IndoorReading]:
    """部屋名 → その部屋のセンサーの最新値。"""
    latest: dict[str, IndoorReading] = {}
    for device in db.scalars(select(Device)):
        reading = db.scalar(
            select(IndoorReading)
            .where(IndoorReading.device_id == device.id)
            .order_by(IndoorReading.measured_at.desc())
            .limit(1)
        )
        if reading is not None:
            latest[device.room_name] = reading
    return latest


def room_indoor(room: Room, readings: dict[str, IndoorReading]) -> IndoorOut:
    """センサーと同じ名前の部屋は室温をセンサーの最新値にし、それ以外はダミー値を返す。"""
    temperature, humidity = room.temperature, room.humidity
    temp_src = hum_src = "dummy"
    reading = readings.get(room.name)
    if reading is not None:
        temperature, temp_src = reading.temperature, "sensor"
        # 湿度は実測のときだけ使う（屋外の湿度で代用した値は室内の値として出さない）
        if reading.humidity_source == "sensor":
            humidity, hum_src = reading.humidity, "sensor"
    return IndoorOut(
        id=reading.id if reading else None,
        room_id=room.id, room_name=room.name,
        temperature=temperature, humidity=humidity,
        temperature_source=temp_src, humidity_source=hum_src,
        discomfort_index=discomfort_index(temperature, humidity),
        measured_at=reading.measured_at if reading else None,
    )


def resolve_room(db: Session, room_id: int | None, classroom: str | None) -> Room | None:
    """room_id か部屋名で部屋を探す。どちらも指定がなければ None、見つからなければ 404。"""
    if room_id is not None:
        room = db.get(Room, room_id)
    elif classroom is not None:
        room = db.scalar(select(Room).where(Room.name == classroom))
    else:
        return None
    if room is None:
        raise HTTPException(404, "room not found")
    return room


def default_room(db: Session) -> Room | None:
    """部屋の指定がないときに使う部屋（センサーの部屋）。"""
    return db.scalar(select(Room).where(Room.name == settings.device_room_name))
