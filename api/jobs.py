from __future__ import annotations

import shutil
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from analysis import AnalysisContext, SwingAnalyser

from api.dtos import AnalysisStatusResponse, JobStatus


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class AnalysisJob:
    run_id: str
    output_dir: Path
    status: JobStatus = "queued"
    progress: float = 0.0
    message: str = "Queued"
    created_at: str = ""
    updated_at: str = ""
    error: str | None = None

    def to_response(self) -> AnalysisStatusResponse:
        return AnalysisStatusResponse(
            run_id=self.run_id,
            status=self.status,
            progress=self.progress,
            message=self.message,
            created_at=self.created_at,
            updated_at=self.updated_at,
            error=self.error,
        )


class JobManager:
    def __init__(self, outputs_dir: Path, max_workers: int = 1) -> None:
        self.outputs_dir = Path(outputs_dir)
        self.outputs_dir.mkdir(parents=True, exist_ok=True)
        self._executor = ThreadPoolExecutor(max_workers=max_workers)
        self._jobs: dict[str, AnalysisJob] = {}
        self._lock = threading.Lock()

    def create(self, upload_path: Path, context: AnalysisContext) -> AnalysisJob:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        run_id = f"swing_{timestamp}_{uuid.uuid4().hex[:8]}"
        output_dir = self.outputs_dir / run_id
        now = utc_now()
        job = AnalysisJob(
            run_id=run_id,
            output_dir=output_dir,
            created_at=now,
            updated_at=now,
        )
        with self._lock:
            self._jobs[run_id] = job
        self._executor.submit(self._run, job, upload_path, context)
        return job

    def get(self, run_id: str) -> AnalysisJob | None:
        with self._lock:
            job = self._jobs.get(run_id)
            if job is not None:
                return job
        output_dir = self.outputs_dir / run_id
        if output_dir.is_dir():
            now = utc_now()
            return AnalysisJob(
                run_id=run_id,
                output_dir=output_dir,
                status="completed",
                progress=1.0,
                message="Loaded from saved run",
                created_at=now,
                updated_at=now,
            )
        return None

    def _run(
        self,
        job: AnalysisJob,
        upload_path: Path,
        context: AnalysisContext,
    ) -> None:
        self._update(job.run_id, status="processing", progress=0.01, message="Starting")

        def progress(message: str, value: float) -> None:
            self._update(job.run_id, status="processing", progress=value, message=message)

        try:
            SwingAnalyser().analyse(upload_path, job.output_dir, progress, context)
        except Exception as exc:
            self._update(
                job.run_id,
                status="failed",
                progress=0.0,
                message="Analysis failed",
                error=str(exc),
            )
        else:
            self._update(
                job.run_id,
                status="completed",
                progress=1.0,
                message="Complete",
            )
        finally:
            try:
                upload_path.unlink()
            except OSError:
                pass

    def _update(
        self,
        run_id: str,
        *,
        status: JobStatus | None = None,
        progress: float | None = None,
        message: str | None = None,
        error: str | None = None,
    ) -> None:
        with self._lock:
            job = self._jobs[run_id]
            if status is not None:
                job.status = status
            if progress is not None:
                job.progress = max(0.0, min(1.0, progress))
            if message is not None:
                job.message = message
            if error is not None:
                job.error = error
            job.updated_at = utc_now()


def save_upload(upload_file, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as file:
        shutil.copyfileobj(upload_file.file, file)

