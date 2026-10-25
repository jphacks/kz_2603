import secrets

from fastapi import APIRouter, Depends
from sqlalchemy import text
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import User
from ..schemas import UserCreate, UserCreated

router = APIRouter()


@router.get("/health", tags=["system"])
def health(db: Session = Depends(get_db)) -> dict[str, str]:
    db.execute(text("SELECT 1"))
    return {"status": "ok"}


@router.post("/users", response_model=UserCreated, status_code=201, tags=["users"])
def create_user(body: UserCreate, db: Session = Depends(get_db)) -> UserCreated:
    """ユーザー登録。返ってきた token を以降 X-User-Token ヘッダで送る。"""
    user = User(name=body.name, token=secrets.token_urlsafe(32))
    db.add(user)
    db.commit()
    return UserCreated(id=user.id, user_id=user.id, name=user.name, token=user.token)
