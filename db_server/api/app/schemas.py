from datetime import datetime, timezone
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from .models import Garment, IndoorReading, Outfit, OutdoorWeather, Recommendation

Verdict = Literal["good", "bad"]
Feedback = Literal["hot", "cold", "just_right"]
Part = Literal["top", "bottom", "outer"]

# AI側は日本語で送ってくるので、保存前に英語へそろえる
FEEDBACK_JA = {"暑かった": "hot", "寒かった": "cold", "ちょうどいい": "just_right"}

# 今日のひろばの体感。画面の表記（日本語）でも受け付ける
Feeling = Literal["comfortable", "slightly_cold", "very_cold", "slightly_hot"]
FEELING_LABELS: dict[str, str] = {
    "comfortable": "快適",
    "slightly_cold": "少し寒い",
    "very_cold": "かなり寒い",
    "slightly_hot": "少し暑い",
}
FEELING_JA = {label: code for code, label in FEELING_LABELS.items()}


class ORM(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# --- 入力 ---------------------------------------------------------------

class UserCreate(BaseModel):
    name: str = Field(min_length=1)


class ReadingCreate(BaseModel):
    temperature: float = Field(ge=-40, le=80, description="室内温度 (°C)")
    humidity: float | None = Field(
        default=None, ge=0, le=100, description="湿度センサーがあれば送る。なければ屋外の湿度で代用"
    )


class FeedbackUpdate(BaseModel):
    feedback: Literal["hot", "cold", "just_right", "暑かった", "寒かった", "ちょうどいい"]

    def normalized(self) -> Feedback:
        return FEEDBACK_JA.get(self.feedback, self.feedback)


class RecommendationCreate(BaseModel):
    top_garment_id: int
    bottom_garment_id: int | None = Field(
        default=None, description="下の服。ワンピースなど上だけで完結するときは省略か null"
    )
    reason: str | None = None


class RoomPostCreate(BaseModel):
    feeling: Literal[
        "comfortable", "slightly_cold", "very_cold", "slightly_hot",
        "快適", "少し寒い", "かなり寒い", "少し暑い",
    ]
    comment: str | None = Field(default=None, max_length=500, description="ひとこと（500文字まで、任意）")

    def normalized_feeling(self) -> Feeling:
        return FEELING_JA.get(self.feeling, self.feeling)

    def normalized_comment(self) -> str | None:
        return (self.comment or "").strip() or None


# --- 出力 ---------------------------------------------------------------

class UserCreated(BaseModel):
    id: int
    user_id: int = Field(description="id と同じ値（AI側の呼び名に合わせたもの）")
    name: str
    token: str


class IndoorOut(BaseModel):
    """室内の値。センサーの実測か、部屋のダミー値か。"""
    id: int | None = Field(description="センサー値のID（ダミーなら null）")
    room_id: int | None = Field(description="今日のひろばの部屋ID（部屋が分からない古いデータは null）")
    room_name: str | None
    temperature: float
    humidity: float | None
    temperature_source: Literal["sensor", "dummy"]
    humidity_source: Literal["sensor", "api", "dummy"] = Field(
        description="sensor: 実測 / api: 屋外の湿度で代用 / dummy: 部屋のダミー値"
    )
    discomfort_index: float | None
    measured_at: datetime | None = Field(description="センサー値の測定時刻（ダミーなら null）")


class OutdoorOut(ORM):
    id: int
    temperature: float | None
    humidity: float | None
    weather_code: int | None
    precipitation: float | None
    discomfort_index: float | None
    forecast_max_temp: float | None
    forecast_min_temp: float | None
    fetched_at: datetime


class Diff(BaseModel):
    """室内 − 屋外"""
    temperature: float | None
    discomfort_index: float | None


class Conditions(BaseModel):
    indoor: IndoorOut | None
    outdoor: OutdoorOut | None
    diff: Diff | None


class LatestIndoor(IndoorOut):
    age_minutes: float | None = Field(description="センサー値が何分前か（ダミーなら null）")
    stale: bool


class LatestOutdoor(OutdoorOut):
    age_minutes: float
    forecast_hourly: list[dict[str, Any]] | None


class LatestConditions(BaseModel):
    indoor: LatestIndoor | None
    outdoor: LatestOutdoor | None
    diff: Diff | None


class GarmentOut(BaseModel):
    id: int
    outfit_id: int
    part: Part
    image_url: str
    taken_at: datetime = Field(description="切り出し元のコーデの撮影日時 (UTC)")
    meta: dict[str, Any] | None
    created_at: datetime


class GarmentWithContext(GarmentOut):
    """服 + 切り出し元コーデの判定と撮影時の気象条件"""
    outfit_verdict: Verdict
    outfit_taken_at: datetime
    user_feedback: Feedback | None
    conditions: Conditions


class OutfitOut(BaseModel):
    id: int
    user_id: int
    image_url: str
    taken_at: datetime
    verdict: Verdict
    comment: str | None
    user_feedback: Feedback | None
    analysis: dict[str, Any] | None
    conditions: Conditions
    garments: list[GarmentOut]


class FeelingCounts(BaseModel):
    comfortable: int = 0
    slightly_cold: int = 0
    very_cold: int = 0
    slightly_hot: int = 0
    total: int = 0


class RoomOut(BaseModel):
    id: int
    name: str
    temperature: float | None
    humidity: float | None
    is_dummy: bool = Field(description="室温がダミー値なら true（センサーのある部屋は false）")
    temperature_source: Literal["sensor", "dummy"]
    humidity_source: Literal["sensor", "dummy"]
    measured_at: datetime | None = Field(description="センサー値の測定時刻（ダミーなら null）")
    stale: bool = Field(description="センサー値が古い（しばらく届いていない）なら true")
    today: FeelingCounts = Field(description="今日（日本時間0時から）の体感ごとの投稿数")


class RoomPostOut(BaseModel):
    id: int
    room_id: int
    feeling: Feeling
    feeling_label: str = Field(description="画面表示用の日本語（快適など）")
    comment: str | None
    created_at: datetime


class RecommendationOut(BaseModel):
    id: int
    user_id: int
    top: GarmentOut
    bottom: GarmentOut | None
    reason: str | None
    conditions: Conditions
    created_at: datetime


# --- 変換 ---------------------------------------------------------------

def _sub(a: float | None, b: float | None) -> float | None:
    return None if a is None or b is None else round(a - b, 1)


def indoor_from_reading(r: IndoorReading | None) -> IndoorOut | None:
    """センサー値をそのまま室内の値にする（部屋の指定がない場合・古いデータ用）。"""
    if r is None:
        return None
    return IndoorOut(
        id=r.id, room_id=None, room_name=r.room_name,
        temperature=r.temperature, humidity=r.humidity,
        temperature_source="sensor", humidity_source=r.humidity_source,
        discomfort_index=r.discomfort_index, measured_at=r.measured_at,
    )


def outfit_indoor(o: Outfit) -> IndoorOut | None:
    """コーデの判定時に記録した室内の値。部屋の記録がない古いコーデはセンサー値を使う。"""
    if o.room_id is None or o.indoor_temperature is None:
        return indoor_from_reading(o.indoor_reading)
    return IndoorOut(
        id=o.indoor_reading_id, room_id=o.room_id, room_name=o.room.name,
        temperature=o.indoor_temperature, humidity=o.indoor_humidity,
        temperature_source=o.indoor_temperature_source, humidity_source=o.indoor_humidity_source,
        discomfort_index=o.indoor_discomfort_index,
        measured_at=o.indoor_reading.measured_at if o.indoor_reading else None,
    )


def build_conditions(indoor: IndoorOut | None, outdoor: OutdoorWeather | None) -> Conditions:
    diff = None
    if indoor and outdoor:
        diff = Diff(
            temperature=_sub(indoor.temperature, outdoor.temperature),
            discomfort_index=_sub(indoor.discomfort_index, outdoor.discomfort_index),
        )
    return Conditions(
        indoor=indoor,
        outdoor=OutdoorOut.model_validate(outdoor) if outdoor else None,
        diff=diff,
    )


def age_minutes(t: datetime) -> float:
    return round((datetime.now(timezone.utc) - t).total_seconds() / 60, 1)


def garment_out(g: Garment) -> GarmentOut:
    return GarmentOut(
        id=g.id, outfit_id=g.outfit_id, part=g.part,
        image_url=f"/garments/{g.id}/image", taken_at=g.outfit.taken_at,
        meta=g.meta, created_at=g.created_at,
    )


def garment_with_context(g: Garment) -> GarmentWithContext:
    o = g.outfit
    return GarmentWithContext(
        **garment_out(g).model_dump(),
        outfit_verdict=o.verdict,
        outfit_taken_at=o.taken_at,
        user_feedback=o.user_feedback,
        conditions=build_conditions(outfit_indoor(o), o.outdoor_weather),
    )


def outfit_out(o: Outfit) -> OutfitOut:
    return OutfitOut(
        id=o.id, user_id=o.user_id, image_url=f"/outfits/{o.id}/image",
        taken_at=o.taken_at, verdict=o.verdict, comment=o.comment,
        user_feedback=o.user_feedback, analysis=o.analysis,
        conditions=build_conditions(outfit_indoor(o), o.outdoor_weather),
        garments=[garment_out(g) for g in o.garments],
    )


def recommendation_out(r: Recommendation) -> RecommendationOut:
    return RecommendationOut(
        id=r.id, user_id=r.user_id,
        top=garment_out(r.top_garment),
        bottom=garment_out(r.bottom_garment) if r.bottom_garment else None,
        reason=r.reason,
        conditions=build_conditions(indoor_from_reading(r.indoor_reading), r.outdoor_weather),
        created_at=r.created_at,
    )
