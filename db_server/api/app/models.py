from datetime import datetime
from typing import Any

from sqlalchemy import BigInteger, CheckConstraint, DateTime, ForeignKey, String, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base

VERDICTS = ("good", "bad")
FEEDBACKS = ("hot", "cold", "just_right")
PARTS = ("top", "bottom", "outer")
FEELINGS = ("comfortable", "slightly_cold", "very_cold", "slightly_hot")


def _in(column: str, values: tuple[str, ...]) -> str:
    return f"{column} IN ({', '.join(repr(v) for v in values)})"


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str]
    token: Mapped[str] = mapped_column(unique=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[int] = mapped_column(primary_key=True)
    room_name: Mapped[str]
    api_key: Mapped[str] = mapped_column(unique=True, index=True)


class IndoorReading(Base):
    __tablename__ = "indoor_readings"
    __table_args__ = (CheckConstraint(_in("humidity_source", ("api", "sensor"))),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    device_id: Mapped[int] = mapped_column(ForeignKey("devices.id"))
    temperature: Mapped[float]
    humidity: Mapped[float | None]
    humidity_source: Mapped[str] = mapped_column(String, default="api")
    discomfort_index: Mapped[float | None]
    # Arduino は時計を持たないので、サーバーの受信時刻を使う
    measured_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), index=True
    )

    device: Mapped[Device] = relationship(lazy="selectin")

    @property
    def room_name(self) -> str:
        return self.device.room_name


class OutdoorWeather(Base):
    __tablename__ = "outdoor_weather"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    temperature: Mapped[float | None]
    humidity: Mapped[float | None]
    weather_code: Mapped[int | None]
    precipitation: Mapped[float | None]
    discomfort_index: Mapped[float | None]
    forecast_max_temp: Mapped[float | None]
    forecast_min_temp: Mapped[float | None]
    forecast_hourly: Mapped[list[dict[str, Any]] | None] = mapped_column(JSONB)
    raw_json: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    fetched_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), index=True
    )


class Outfit(Base):
    __tablename__ = "outfits"
    __table_args__ = (
        CheckConstraint(_in("verdict", VERDICTS)),
        CheckConstraint(_in("user_feedback", FEEDBACKS)),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    image_path: Mapped[str]
    taken_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    indoor_reading_id: Mapped[int | None] = mapped_column(ForeignKey("indoor_readings.id"))
    outdoor_weather_id: Mapped[int | None] = mapped_column(ForeignKey("outdoor_weather.id"))
    verdict: Mapped[str]
    comment: Mapped[str | None]
    user_feedback: Mapped[str | None]
    # AIの分析結果（level, score, items など）。中身はAI側が決める
    analysis: Mapped[dict[str, Any] | None] = mapped_column(JSONB)

    # 判定した部屋と、そのときの室内の値（センサーの部屋は実測、ほかはダミー）
    room_id: Mapped[int | None] = mapped_column(ForeignKey("rooms.id"), index=True)
    indoor_temperature: Mapped[float | None]
    indoor_humidity: Mapped[float | None]
    indoor_temperature_source: Mapped[str | None]
    indoor_humidity_source: Mapped[str | None]
    indoor_discomfort_index: Mapped[float | None]

    room: Mapped["Room | None"] = relationship(lazy="selectin")
    indoor_reading: Mapped[IndoorReading | None] = relationship(lazy="selectin")
    outdoor_weather: Mapped[OutdoorWeather | None] = relationship(lazy="selectin")
    garments: Mapped[list["Garment"]] = relationship(
        back_populates="outfit", lazy="selectin", order_by="Garment.id"
    )


class Garment(Base):
    __tablename__ = "garments"
    __table_args__ = (CheckConstraint(_in("part", PARTS)),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    outfit_id: Mapped[int] = mapped_column(ForeignKey("outfits.id"), index=True)
    part: Mapped[str]
    image_path: Mapped[str]
    # AIが付ける服の情報（type, color, clo, hidden など）。中身はAI側が決める
    meta: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    outfit: Mapped[Outfit] = relationship(back_populates="garments", lazy="selectin")


class Recommendation(Base):
    __tablename__ = "recommendations"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    top_garment_id: Mapped[int] = mapped_column(ForeignKey("garments.id"))
    # ワンピースなど上だけで完結する組み合わせは null
    bottom_garment_id: Mapped[int | None] = mapped_column(ForeignKey("garments.id"))
    indoor_reading_id: Mapped[int | None] = mapped_column(ForeignKey("indoor_readings.id"))
    outdoor_weather_id: Mapped[int | None] = mapped_column(ForeignKey("outdoor_weather.id"))
    reason: Mapped[str | None]
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    top_garment: Mapped[Garment] = relationship(foreign_keys=[top_garment_id], lazy="selectin")
    bottom_garment: Mapped[Garment | None] = relationship(foreign_keys=[bottom_garment_id], lazy="selectin")
    indoor_reading: Mapped[IndoorReading | None] = relationship(lazy="selectin")
    outdoor_weather: Mapped[OutdoorWeather | None] = relationship(lazy="selectin")


class Room(Base):
    """今日のひろばの場所。室温・湿度は当面ダミー値。"""
    __tablename__ = "rooms"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(unique=True)
    temperature: Mapped[float | None]
    humidity: Mapped[float | None]


class RoomPost(Base):
    """今日のひろばへの体感の投稿。"""
    __tablename__ = "room_posts"
    __table_args__ = (
        CheckConstraint(_in("feeling", FEELINGS)),
        CheckConstraint("char_length(comment) <= 500"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    room_id: Mapped[int] = mapped_column(ForeignKey("rooms.id"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    feeling: Mapped[str]
    comment: Mapped[str | None]
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), index=True
    )
