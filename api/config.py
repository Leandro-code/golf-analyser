from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv
from pydantic import BaseModel


PROJECT_ROOT = Path(__file__).resolve().parents[1]
load_dotenv(PROJECT_ROOT / ".env")


class ApiSettings(BaseModel):
    outputs_dir: Path = Path("outputs")
    bearer_token: str | None = None


@lru_cache
def get_settings() -> ApiSettings:
    return ApiSettings(
        outputs_dir=Path(os.environ.get("GOLF_ANALYSER_OUTPUTS_DIR", "outputs")),
        bearer_token=os.environ.get("GOLF_ANALYSER_API_TOKEN") or None,
    )
