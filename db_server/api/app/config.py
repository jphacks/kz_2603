from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str
    image_dir: str = "/data/images"

    device_api_key: str
    device_room_name: str = "講義室"

    weather_latitude: float = 35.681
    weather_longitude: float = 139.767
    weather_interval_minutes: int = 30

    # センサーの値がこれより古ければ stale 扱いにする（分）
    stale_after_minutes: int = 15


settings = Settings()
