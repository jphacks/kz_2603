from fastapi import Depends, Header, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from .db import get_db
from .models import Device, User


def current_user(
    x_user_token: str = Header(description="POST /users で発行されたトークン"),
    db: Session = Depends(get_db),
) -> User:
    user = db.scalar(select(User).where(User.token == x_user_token))
    if user is None:
        raise HTTPException(401, "invalid user token")
    return user


def require_self(user_id: int, user: User) -> None:
    if user.id != user_id:
        raise HTTPException(403, "cannot access another user's data")


def current_device(
    x_device_key: str = Header(description="センサーのAPIキー"),
    db: Session = Depends(get_db),
) -> Device:
    device = db.scalar(select(Device).where(Device.api_key == x_device_key))
    if device is None:
        raise HTTPException(401, "invalid device key")
    return device
