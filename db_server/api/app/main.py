import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import FastAPI
from sqlalchemy import select, text

from .config import settings
from .db import Base, SessionLocal, engine
from .models import Device, Room
from .routers import conditions, outfits, plaza, recommendations, system
from .weather import fetch_weather

logging.basicConfig(level=logging.INFO)

# 今日のひろばの場所（名前, ダミー室温, ダミー湿度）。rooms が空のときだけ登録する
DEFAULT_ROOMS = [
    ("図書館", 21.0, 48.0),
    ("201教室", 24.2, 62.0),
    ("学生食堂", 26.5, 70.0),
    ("実験室3", 19.5, 42.0),
]


def _ensure_device() -> None:
    """.env の DEVICE_API_KEY のセンサーを登録し、部屋名を DEVICE_ROOM_NAME に合わせる（センサーは1台の想定）。"""
    with SessionLocal() as db:
        device = db.scalar(select(Device).where(Device.api_key == settings.device_api_key))
        if device is None:
            db.add(Device(room_name=settings.device_room_name, api_key=settings.device_api_key))
        else:
            device.room_name = settings.device_room_name
        db.commit()


def _ensure_rooms() -> None:
    with SessionLocal() as db:
        if db.scalar(select(Room.id).limit(1)) is None:
            db.add_all(Room(name=n, temperature=t, humidity=h) for n, t, h in DEFAULT_ROOMS)
            db.commit()


# create_all は既存テーブルに列を足さないので、後から追加した列はここで足す
_ADDED_COLUMNS = [
    "ALTER TABLE garments ADD COLUMN IF NOT EXISTS meta JSONB",
    "ALTER TABLE outfits ADD COLUMN IF NOT EXISTS analysis JSONB",
    "ALTER TABLE outfits ADD COLUMN IF NOT EXISTS room_id INTEGER REFERENCES rooms(id)",
    "CREATE INDEX IF NOT EXISTS ix_outfits_room_id ON outfits (room_id)",
    "ALTER TABLE outfits ADD COLUMN IF NOT EXISTS indoor_temperature REAL",
    "ALTER TABLE outfits ADD COLUMN IF NOT EXISTS indoor_humidity REAL",
    "ALTER TABLE outfits ADD COLUMN IF NOT EXISTS indoor_temperature_source VARCHAR",
    "ALTER TABLE outfits ADD COLUMN IF NOT EXISTS indoor_humidity_source VARCHAR",
    "ALTER TABLE outfits ADD COLUMN IF NOT EXISTS indoor_discomfort_index REAL",
    # 下の服なし（ワンピースなど）のおすすめを保存できるようにする
    "ALTER TABLE recommendations ALTER COLUMN bottom_garment_id DROP NOT NULL",
]


def _add_columns() -> None:
    with engine.begin() as conn:
        for sql in _ADDED_COLUMNS:
            conn.execute(text(sql))


@asynccontextmanager
async def lifespan(app: FastAPI):
    # ハッカソン用: マイグレーションは使わず、起動時にテーブルを作る
    Base.metadata.create_all(engine)
    _add_columns()
    _ensure_device()
    _ensure_rooms()

    scheduler = BackgroundScheduler(timezone="UTC")
    scheduler.add_job(
        fetch_weather, "interval",
        minutes=settings.weather_interval_minutes, next_run_time=datetime.now(timezone.utc),
    )
    scheduler.start()
    yield
    scheduler.shutdown(wait=False)


app = FastAPI(title="Outfit AI DB Server", version="0.2.0", lifespan=lifespan)
app.include_router(system.router)
app.include_router(conditions.router)
app.include_router(outfits.router)
app.include_router(recommendations.router)
app.include_router(plaza.router)
