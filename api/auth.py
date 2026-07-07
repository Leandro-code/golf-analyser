from __future__ import annotations

from fastapi import Depends, Header, HTTPException, status

from api.config import ApiSettings, get_settings


def require_bearer_token(
    authorization: str | None = Header(default=None),
    settings: ApiSettings = Depends(get_settings),
) -> None:
    if settings.bearer_token is None:
        return
    expected = f"Bearer {settings.bearer_token}"
    if authorization != expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or invalid bearer token.",
            headers={"WWW-Authenticate": "Bearer"},
        )

