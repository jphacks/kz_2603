from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..auth import current_user, require_self
from ..db import get_db
from ..models import Garment, Recommendation, User
from ..queries import latest_indoor, latest_outdoor
from ..schemas import RecommendationCreate, RecommendationOut, recommendation_out

router = APIRouter(tags=["recommendations"])


def _own_garment(db: Session, garment_id: int, user_id: int, part: str) -> Garment:
    garment = db.get(Garment, garment_id)
    if garment is None or garment.user_id != user_id:
        raise HTTPException(422, f"garment {garment_id} not found for this user")
    if garment.part != part:
        raise HTTPException(422, f"garment {garment_id} is '{garment.part}', not '{part}'")
    return garment


# --- ③ 今日のおすすめ ---------------------------------------------------

@router.post(
    "/users/{user_id}/recommendations", response_model=RecommendationOut, status_code=201
)
def create_recommendation(
    user_id: int,
    body: RecommendationCreate,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> RecommendationOut:
    """AIが選んだ上下の組み合わせを、提案時点の気象データと一緒に保存する。下の服は省略できる。"""
    require_self(user_id, user)
    _own_garment(db, body.top_garment_id, user_id, "top")
    if body.bottom_garment_id is not None:
        _own_garment(db, body.bottom_garment_id, user_id, "bottom")
    indoor, outdoor = latest_indoor(db), latest_outdoor(db)
    rec = Recommendation(
        user_id=user_id,
        top_garment_id=body.top_garment_id,
        bottom_garment_id=body.bottom_garment_id,
        reason=body.reason,
        indoor_reading_id=indoor.id if indoor else None,
        outdoor_weather_id=outdoor.id if outdoor else None,
    )
    db.add(rec)
    db.commit()
    db.refresh(rec)
    return recommendation_out(rec)


@router.get("/users/{user_id}/recommendations/latest", response_model=RecommendationOut)
def get_latest_recommendation(
    user_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)
) -> RecommendationOut:
    require_self(user_id, user)
    rec = db.scalar(
        select(Recommendation)
        .where(Recommendation.user_id == user_id)
        .order_by(Recommendation.created_at.desc(), Recommendation.id.desc())
        .limit(1)
    )
    if rec is None:
        raise HTTPException(404, "no recommendation yet")
    return recommendation_out(rec)
