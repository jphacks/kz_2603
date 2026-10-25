from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import IndoorReading, OutdoorWeather


def latest_indoor(db: Session) -> IndoorReading | None:
    return db.scalar(select(IndoorReading).order_by(IndoorReading.measured_at.desc()).limit(1))


def latest_outdoor(db: Session) -> OutdoorWeather | None:
    return db.scalar(select(OutdoorWeather).order_by(OutdoorWeather.fetched_at.desc()).limit(1))
