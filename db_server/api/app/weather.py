import logging

import httpx

from .config import settings
from .db import SessionLocal
from .discomfort import discomfort_index
from .models import OutdoorWeather

log = logging.getLogger(__name__)

OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"


def fetch_weather() -> None:
    """Open-Meteo から現在値と今日の予報を取得して outdoor_weather に保存する。"""
    params = {
        "latitude": settings.weather_latitude,
        "longitude": settings.weather_longitude,
        "current": "temperature_2m,relative_humidity_2m,weather_code,precipitation",
        "hourly": "temperature_2m,relative_humidity_2m,precipitation_probability",
        "daily": "temperature_2m_max,temperature_2m_min",
        "timezone": "Asia/Tokyo",
        "forecast_days": 1,
    }
    try:
        res = httpx.get(OPEN_METEO_URL, params=params, timeout=10)
        res.raise_for_status()
        data = res.json()
    except (httpx.HTTPError, ValueError):
        log.exception("failed to fetch weather")
        return

    current = data.get("current", {})
    daily = data.get("daily", {})
    hourly = data.get("hourly", {})
    temperature = current.get("temperature_2m")
    humidity = current.get("relative_humidity_2m")

    forecast_hourly = [
        {"time": t, "temperature": temp, "humidity": hum, "precipitation_probability": prob}
        for t, temp, hum, prob in zip(
            hourly.get("time", []),
            hourly.get("temperature_2m", []),
            hourly.get("relative_humidity_2m", []),
            hourly.get("precipitation_probability", []),
        )
    ]

    with SessionLocal() as db:
        db.add(
            OutdoorWeather(
                temperature=temperature,
                humidity=humidity,
                weather_code=current.get("weather_code"),
                precipitation=current.get("precipitation"),
                discomfort_index=discomfort_index(temperature, humidity),
                forecast_max_temp=(daily.get("temperature_2m_max") or [None])[0],
                forecast_min_temp=(daily.get("temperature_2m_min") or [None])[0],
                forecast_hourly=forecast_hourly,
                raw_json=data,
            )
        )
        db.commit()
    log.info("weather fetched: %s°C %s%%", temperature, humidity)
