import uuid
from pathlib import Path

from fastapi import HTTPException, UploadFile
from fastapi.responses import FileResponse

from .config import settings

EXTENSIONS = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}


def save_image(upload: UploadFile, kind: str) -> str:
    """画像を保存して IMAGE_DIR からの相対パスを返す。kind は 'outfits' / 'garments'。"""
    ext = EXTENSIONS.get(upload.content_type or "")
    if ext is None:
        raise HTTPException(415, f"unsupported image type: {upload.content_type}")
    relative = f"{kind}/{uuid.uuid4().hex}{ext}"
    dest = Path(settings.image_dir) / relative
    dest.parent.mkdir(parents=True, exist_ok=True)
    with dest.open("wb") as f:
        while chunk := upload.file.read(1024 * 1024):
            f.write(chunk)
    return relative


def image_response(relative: str) -> FileResponse:
    path = Path(settings.image_dir) / relative
    if not path.is_file():
        raise HTTPException(404, "image file not found")
    return FileResponse(path)
