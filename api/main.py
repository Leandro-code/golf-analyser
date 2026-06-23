from __future__ import annotations

import tempfile
from functools import lru_cache
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, HTTPException, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import ValidationError

from analysis import AnalysisContext, SwingAnalyser, list_analysis_runs, load_analysis_result
from analysis.llm_assessment import LLMAssessmentError, generate_llm_assessment

from api.artifacts import artifact_path
from api.auth import require_bearer_token
from api.config import ApiSettings, get_settings
from api.dtos import (
    AnalysisCreateResponse,
    AnalysisResultResponse,
    AnalysisStatusResponse,
    AnalysisSummaryResponse,
    PhaseConfirmationRequest,
    result_to_response,
    result_to_summary,
)
from api.jobs import JobManager, save_upload


app = FastAPI(title="Golf Analyser API", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origin_regex=r"^https?://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$",
    allow_methods=["*"],
    allow_headers=["*"],
)


@lru_cache
def get_job_manager(outputs_dir: str) -> JobManager:
    return JobManager(Path(outputs_dir))


def job_manager(settings: ApiSettings = Depends(get_settings)) -> JobManager:
    return get_job_manager(str(settings.outputs_dir))


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post(
    "/analyses",
    response_model=AnalysisCreateResponse,
    dependencies=[Depends(require_bearer_token)],
    status_code=status.HTTP_202_ACCEPTED,
)
def create_analysis(
    video: UploadFile = File(...),
    handedness: str = Form(...),
    camera_view: str = Form(...),
    club_family: str = Form(...),
    swing_type: str = Form("full_swing"),
    manager: JobManager = Depends(job_manager),
) -> AnalysisCreateResponse:
    try:
        context = AnalysisContext(
            handedness=handedness,
            camera_view=camera_view,
            club_family=club_family,
            swing_type=swing_type,
        )
    except ValidationError as exc:
        raise HTTPException(status_code=422, detail=exc.errors()) from exc

    suffix = Path(video.filename or "upload.mp4").suffix or ".mp4"
    upload_path = Path(tempfile.mkdtemp(prefix="golf_upload_")) / f"source{suffix}"
    save_upload(video, upload_path)
    job = manager.create(upload_path, context)
    return AnalysisCreateResponse(
        run_id=job.run_id,
        status_url=f"/analyses/{job.run_id}/status",
        result_url=f"/analyses/{job.run_id}",
        status=job.to_response(),
    )


@app.get(
    "/analyses",
    response_model=list[AnalysisSummaryResponse],
    dependencies=[Depends(require_bearer_token)],
)
def list_analyses(settings: ApiSettings = Depends(get_settings)) -> list[AnalysisSummaryResponse]:
    summaries = []
    for run_dir in list_analysis_runs(settings.outputs_dir):
        try:
            summaries.append(result_to_summary(load_analysis_result(run_dir)))
        except Exception:
            continue
    return summaries


@app.get(
    "/analyses/{run_id}",
    response_model=AnalysisResultResponse,
    dependencies=[Depends(require_bearer_token)],
)
def get_analysis(run_id: str, settings: ApiSettings = Depends(get_settings)) -> AnalysisResultResponse:
    result = _load_or_404(settings.outputs_dir, run_id)
    return result_to_response(result)


@app.get(
    "/analyses/{run_id}/status",
    response_model=AnalysisStatusResponse,
    dependencies=[Depends(require_bearer_token)],
)
def get_status(run_id: str, manager: JobManager = Depends(job_manager)) -> AnalysisStatusResponse:
    job = manager.get(run_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Analysis run not found.")
    return job.to_response()


@app.get(
    "/analyses/{run_id}/artifacts/{artifact_name}",
    dependencies=[Depends(require_bearer_token)],
)
def get_artifact(
    run_id: str,
    artifact_name: str,
    settings: ApiSettings = Depends(get_settings),
) -> FileResponse:
    result = _load_or_404(settings.outputs_dir, run_id)
    path = artifact_path(result, artifact_name)
    if path is None:
        raise HTTPException(status_code=404, detail="Artifact not found.")
    return FileResponse(path)


@app.post(
    "/analyses/{run_id}/phases/redetect",
    response_model=AnalysisResultResponse,
    dependencies=[Depends(require_bearer_token)],
)
def redetect_phases(
    run_id: str,
    settings: ApiSettings = Depends(get_settings),
) -> AnalysisResultResponse:
    result = _load_or_404(settings.outputs_dir, run_id)
    updated = SwingAnalyser().redetect_phases(result)
    return result_to_response(updated)


@app.post(
    "/analyses/{run_id}/phases/confirm",
    response_model=AnalysisResultResponse,
    dependencies=[Depends(require_bearer_token)],
)
def confirm_phases(
    run_id: str,
    request: PhaseConfirmationRequest,
    settings: ApiSettings = Depends(get_settings),
) -> AnalysisResultResponse:
    result = _load_or_404(settings.outputs_dir, run_id)
    try:
        updated = SwingAnalyser().confirm_phase_markers(result, *request.frame_indices)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return result_to_response(updated)


@app.post(
    "/analyses/{run_id}/llm-assessment",
    response_model=AnalysisResultResponse,
    dependencies=[Depends(require_bearer_token)],
)
def create_llm_assessment(
    run_id: str,
    settings: ApiSettings = Depends(get_settings),
) -> AnalysisResultResponse:
    result = _load_or_404(settings.outputs_dir, run_id)
    try:
        updated = generate_llm_assessment(result)
    except LLMAssessmentError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return result_to_response(updated)


def _load_or_404(outputs_dir: Path, run_id: str):
    if Path(run_id).name != run_id:
        raise HTTPException(status_code=404, detail="Analysis run not found.")
    run_dir = Path(outputs_dir) / run_id
    try:
        return load_analysis_result(run_dir)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Analysis run not found.") from exc
