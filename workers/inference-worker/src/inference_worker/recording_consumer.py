import json
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time

from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable
from urllib.request import Request, urlopen

from confluent_kafka import (
    Consumer,
    KafkaError,
    KafkaException,
)

from inference_worker.job import (
    AnalyticsJob,
    UnsupportedAnalyticsJob,
)
from inference_worker.worker_events import (
    WorkerEventPublisher,
    WorkerHeartbeat,
)


log = logging.getLogger(__name__)


@dataclass
class RealtimeProcess:
    job: AnalyticsJob
    process: subprocess.Popen
    stop_requested: bool = False


class RecordingStopped(Exception):
    """Raised when a running recording job is cancelled by the user."""


@dataclass
class RecordingTask:
    job: AnalyticsJob
    stop_event: threading.Event
    thread: threading.Thread


def download_recording(
    recording_id: str,
    target: Path,
    source_url: str | None = None,
) -> None:

    base_url = os.getenv(
        "RECORDING_SERVICE_URL",
        "http://recording-service:8095",
    ).rstrip("/")

    url = (
        source_url
        or (
            f"{base_url}"
            f"/api/recording-sources/{recording_id}"
        )
    )

    log.info(
        "Downloading recording recordingId=%s url=%s",
        recording_id,
        url,
    )

    request = Request(
        url,
        headers={
            "Accept": "video/x-matroska",
        },
    )

    with (
        urlopen(
            request,
            timeout=600,
        ) as response,
        target.open("wb") as output,
    ):

        shutil.copyfileobj(
            response,
            output,
            length=1024 * 1024,
        )

    if target.stat().st_size == 0:

        raise RuntimeError(
            f"Downloaded recording is empty: "
            f"{recording_id}"
        )


def run_inference(
    job: AnalyticsJob,
    stop_event: threading.Event,
    progress_callback: Callable[[dict], None],
) -> dict:
    if job.job_type != "RECORDING":
        raise UnsupportedAnalyticsJob(
            "recording_consumer only supports "
            f"RECORDING jobs, got {job.job_type}"
        )

    if not job.recording_id:
        raise ValueError(
            "recordingId is required for RECORDING jobs"
        )

    recording_id = job.recording_id
    camera_id = job.camera_id

    work_root = Path(
        os.getenv(
            "INFERENCE_WORK_DIRECTORY",
            "/data/work",
        )
    )

    work_root.mkdir(
        parents=True,
        exist_ok=True,
    )

    with tempfile.TemporaryDirectory(
        prefix=f"{recording_id}-",
        dir=work_root,
    ) as temporary_directory:

        job_directory = Path(
            temporary_directory
        )

        source = (
            job_directory
            / "source.mkv"
        )

        if stop_event.is_set():
            raise RecordingStopped()

        download_recording(
            recording_id,
            source,
            source_url=job.source.url,
        )

        environment = os.environ.copy()

        environment.update(
            {
                "WORKER_MODE": "single",
                "ANALYTICS_INPUT_FILE": str(source),
                "CAMERA_ID": camera_id,
                "RECORDING_ID": recording_id,
                "ANALYTICS_OUTPUT_VIDEO": str(
                    job_directory
                    / "annotated.mp4"
                ),
                "ANALYTICS_OUTPUT_EVENTS": str(
                    job_directory
                    / "events.jsonl"
                ),
                "ANALYTICS_PROGRESS_FILE": str(
                    job_directory / "progress.json"
                ),
            }
        )

        environment["YOLO_CLASSES"] = ",".join(
            str(value)
            for value in job.profile.classes
        )
        if job.profile.lines:
            environment["ANALYTICS_LINES_JSON"] = json.dumps(
                [asdict(line) for line in job.profile.lines]
            )

        optional_environment = {
            "YOLO_MODEL": job.profile.model,
            "YOLO_CONFIDENCE": job.profile.confidence,
            "YOLO_DEVICE": job.profile.device_preference,
            "LINE_POSITION": job.profile.line_position,
        }

        for key, value in optional_environment.items():
            if value is not None:
                environment[key] = str(value)

        process = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "inference_worker.main",
            ],
            env=environment,
        )

        progress_file = job_directory / "progress.json"
        last_progress: dict = {}
        while process.poll() is None:
            if stop_event.wait(1.0):
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                raise RecordingStopped()

            if progress_file.exists():
                try:
                    progress = json.loads(progress_file.read_text(encoding="utf-8"))
                    if progress != last_progress:
                        last_progress = progress
                        progress_callback(progress)
                except (OSError, json.JSONDecodeError):
                    # The producer replaces the file atomically; retry on the next tick.
                    pass

        if process.returncode != 0:
            raise subprocess.CalledProcessError(
                process.returncode,
                [sys.executable, "-m", "inference_worker.main"],
            )

        return last_progress


def realtime_environment(job: AnalyticsJob) -> dict[str, str]:
    if not job.source.url:
        raise ValueError("source.url is required for REALTIME jobs")
    if job.source.type != "RTSP":
        raise ValueError(
            f"REALTIME source.type must be RTSP, got {job.source.type}"
        )

    environment = os.environ.copy()
    environment.update(
        {
            "ANALYTICS_RTSP_URL": job.source.url,
            "ANALYTICS_JOB_ID": job.job_id,
            "CAMERA_ID": job.camera_id,
            "RTSP_TRANSPORT": job.source.transport or "tcp",
            "YOLO_CLASSES": ",".join(str(value) for value in job.profile.classes),
        }
    )
    optional_environment = {
        "YOLO_MODEL": job.profile.model,
        "YOLO_CONFIDENCE": job.profile.confidence,
        "YOLO_DEVICE": job.profile.device_preference,
        "LINE_POSITION": job.profile.line_position,
        "ANALYTICS_TARGET_FPS": job.profile.target_fps,
    }
    if job.profile.lines:
        environment["ANALYTICS_LINES_JSON"] = json.dumps(
            [asdict(line) for line in job.profile.lines]
        )
    for key, value in optional_environment.items():
        if value is not None:
            environment[key] = str(value)
    return environment


def start_realtime(job: AnalyticsJob) -> RealtimeProcess:
    process = subprocess.Popen(
        [sys.executable, "-m", "inference_worker.realtime_main"],
        env=realtime_environment(job),
    )
    return RealtimeProcess(job=job, process=process)


def stop_realtime(running: RealtimeProcess, timeout: float = 15.0) -> None:
    running.stop_requested = True
    running.process.terminate()
    try:
        running.process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        log.warning(
            "Realtime process did not stop gracefully jobId=%s; killing",
            running.job.job_id,
        )
        running.process.kill()
        running.process.wait(timeout=5)


def process_recording_job(
    job: AnalyticsJob,
    stop_event: threading.Event,
    worker_events: WorkerEventPublisher,
) -> None:
    retry_delay = 10

    def publish_progress(details: dict) -> None:
        worker_events.publish_status(
            job_id=job.job_id,
            camera_id=job.camera_id,
            recording_id=job.recording_id,
            job_type=job.job_type,
            status="RUNNING",
            details=details,
        )

    while not stop_event.is_set():
        try:
            final_progress = run_inference(job, stop_event, publish_progress)
            worker_events.publish_status(
                job_id=job.job_id,
                camera_id=job.camera_id,
                recording_id=job.recording_id,
                job_type=job.job_type,
                status="COMPLETED",
                details=final_progress,
            )
            log.info(
                "Inference completed jobId=%s recordingId=%s",
                job.job_id,
                job.recording_id,
            )
            return
        except RecordingStopped:
            worker_events.publish_status(
                job_id=job.job_id,
                camera_id=job.camera_id,
                recording_id=job.recording_id,
                job_type=job.job_type,
                status="STOPPED",
                details={"cancelled": True},
            )
            log.info("Inference stopped jobId=%s", job.job_id)
            return
        except Exception:
            log.exception(
                "Inference failed jobId=%s recordingId=%s; retrying in %ss",
                job.job_id,
                job.recording_id,
                retry_delay,
            )
            worker_events.publish_status(
                job_id=job.job_id,
                camera_id=job.camera_id,
                recording_id=job.recording_id,
                job_type=job.job_type,
                status="RETRYING",
                details={"retryDelaySeconds": retry_delay},
            )
            if stop_event.wait(retry_delay):
                continue
            retry_delay = min(retry_delay * 2, 300)

    worker_events.publish_status(
        job_id=job.job_id,
        camera_id=job.camera_id,
        recording_id=job.recording_id,
        job_type=job.job_type,
        status="STOPPED",
        details={"cancelled": True},
    )


def main() -> None:

    logging.basicConfig(
        level=logging.INFO,
        format=(
            "%(asctime)s %(levelname)s "
            "%(name)s - %(message)s"
        ),
    )

    topics = [
        value.strip()
        for value in os.getenv(
            "KAFKA_INPUT_TOPICS",
            os.getenv(
                "KAFKA_INPUT_TOPIC",
                "recording.events",
            ),
        ).split(",")
        if value.strip()
    ]

    worker_events = WorkerEventPublisher()
    heartbeat = WorkerHeartbeat(worker_events)
    heartbeat.start()
    realtime_by_camera: dict[str, RealtimeProcess] = {}
    recording_task: RecordingTask | None = None
    execution_mode = os.getenv(
        "INFERENCE_EXECUTION_MODE", "process"
    ).strip().lower()
    if execution_mode not in {"process", "batched"}:
        raise ValueError(
            "INFERENCE_EXECUTION_MODE must be process or batched"
        )
    multistream = None
    if execution_mode == "batched":
        from inference_worker.multistream import MultistreamManager

        multistream = MultistreamManager()

    consumer = Consumer(
        {
            "bootstrap.servers": os.getenv(
                "KAFKA_BOOTSTRAP_SERVERS",
                "kafka:29092",
            ),
            "group.id": os.getenv(
                "KAFKA_CONSUMER_GROUP",
                "inference-workers",
            ),
            "auto.offset.reset": os.getenv(
                "KAFKA_AUTO_OFFSET_RESET",
                "earliest",
            ),
            "enable.auto.commit": False,
            "max.poll.interval.ms": int(
                os.getenv(
                    "KAFKA_MAX_POLL_INTERVAL_MS",
                    "86400000",
                )
            ),
        }
    )

    consumer.subscribe(
        topics
    )

    log.info(
        "Waiting for analytics jobs topics=%s workerId=%s executionMode=%s",
        topics,
        worker_events.worker_id,
        execution_mode,
    )

    try:

        while True:

            if recording_task is not None and not recording_task.thread.is_alive():
                recording_task.thread.join()
                recording_task = None
                heartbeat.set_active_jobs(len(realtime_by_camera))

            if multistream is not None:
                multistream.check_health()

            for camera_id, running in list(realtime_by_camera.items()):
                exit_code = running.process.poll()
                if exit_code is None:
                    continue
                realtime_by_camera.pop(camera_id)
                heartbeat.set_active_jobs(len(realtime_by_camera))
                worker_events.publish_status(
                    job_id=running.job.job_id,
                    camera_id=running.job.camera_id,
                    recording_id=None,
                    job_type="REALTIME",
                    status=(
                        "STOPPED"
                        if running.stop_requested and exit_code == 0
                        else "FAILED"
                    ),
                    details={"exitCode": exit_code},
                )
                log.info(
                    "Realtime process exited jobId=%s cameraId=%s exitCode=%s",
                    running.job.job_id,
                    camera_id,
                    exit_code,
                )

            message = consumer.poll(
                1.0
            )

            if message is None:
                continue

            if message.error():

                if (
                    message.error().code()
                    == KafkaError._PARTITION_EOF
                ):
                    continue

                raise KafkaException(
                    message.error()
                )

            event = json.loads(
                message.value()
                .decode("utf-8")
            )

            try:
                job = AnalyticsJob.from_message(event)
            except (UnsupportedAnalyticsJob, ValueError):

                log.warning(
                    "Skipping unsupported analytics message: %s",
                    event,
                    exc_info=True,
                )

                consumer.commit(
                    message=message,
                    asynchronous=False,
                )

                continue

            if job.job_type == "REALTIME":
                running = realtime_by_camera.get(job.camera_id)
                batched_job = (
                    multistream.job_for(job.camera_id)
                    if multistream is not None
                    else None
                )
                current_job = running.job if running is not None else batched_job

                if job.action == "STOP":
                    if current_job is None:
                        worker_events.publish_status(
                            job_id=job.job_id,
                            camera_id=job.camera_id,
                            recording_id=None,
                            job_type="REALTIME",
                            status="REJECTED",
                            details={"errorCode": "JOB_NOT_RUNNING"},
                        )
                    else:
                        if multistream is not None:
                            multistream.stop(job.camera_id)
                        else:
                            assert running is not None
                            stop_realtime(running)
                            realtime_by_camera.pop(job.camera_id, None)
                        active_jobs = (
                            multistream.active_jobs
                            if multistream is not None
                            else len(realtime_by_camera)
                        )
                        heartbeat.set_active_jobs(active_jobs)
                        worker_events.publish_status(
                            job_id=current_job.job_id,
                            camera_id=current_job.camera_id,
                            recording_id=None,
                            job_type="REALTIME",
                            status="STOPPED",
                            details={"stoppedByJobId": job.job_id},
                        )
                    consumer.commit(message=message, asynchronous=False)
                    continue

                if current_job is not None:
                    status = (
                        "RUNNING"
                        if current_job.job_id == job.job_id
                        else "REJECTED"
                    )
                    details = (
                        {"idempotent": True}
                        if status == "RUNNING"
                        else {
                            "errorCode": "CAMERA_ALREADY_RUNNING",
                            "runningJobId": current_job.job_id,
                        }
                    )
                    worker_events.publish_status(
                        job_id=job.job_id,
                        camera_id=job.camera_id,
                        recording_id=None,
                        job_type="REALTIME",
                        status=status,
                        details=details,
                    )
                    consumer.commit(message=message, asynchronous=False)
                    continue

                active_jobs = (
                    multistream.active_jobs
                    if multistream is not None
                    else len(realtime_by_camera)
                )
                if active_jobs >= heartbeat.max_jobs:
                    worker_events.publish_status(
                        job_id=job.job_id,
                        camera_id=job.camera_id,
                        recording_id=None,
                        job_type="REALTIME",
                        status="REJECTED",
                        details={"errorCode": "WORKER_BUSY"},
                    )
                    consumer.commit(message=message, asynchronous=False)
                    continue

                try:
                    if multistream is not None:
                        multistream.start(job)
                    else:
                        running = start_realtime(job)
                except Exception as error:
                    log.exception("Cannot start realtime job jobId=%s", job.job_id)
                    worker_events.publish_status(
                        job_id=job.job_id,
                        camera_id=job.camera_id,
                        recording_id=None,
                        job_type="REALTIME",
                        status="REJECTED",
                        details={
                            "errorCode": "INVALID_REALTIME_JOB",
                            "message": str(error),
                        },
                    )
                else:
                    details = {"executionMode": execution_mode}
                    if multistream is None:
                        realtime_by_camera[job.camera_id] = running
                        details["pid"] = running.process.pid
                    active_jobs = (
                        multistream.active_jobs
                        if multistream is not None
                        else len(realtime_by_camera)
                    )
                    heartbeat.set_active_jobs(active_jobs)
                    worker_events.publish_status(
                        job_id=job.job_id,
                        camera_id=job.camera_id,
                        recording_id=None,
                        job_type="REALTIME",
                        status="RUNNING",
                        details=details,
                    )
                    log.info(
                        "Realtime inference started jobId=%s cameraId=%s mode=%s",
                        job.job_id,
                        job.camera_id,
                        execution_mode,
                    )
                consumer.commit(message=message, asynchronous=False)
                continue

            if job.action == "STOP":
                if (
                    recording_task is not None
                    and recording_task.job.recording_id == job.recording_id
                ):
                    recording_task.stop_event.set()
                    log.info(
                        "Recording inference stop requested jobId=%s recordingId=%s",
                        recording_task.job.job_id,
                        recording_task.job.recording_id,
                    )
                consumer.commit(message=message, asynchronous=False)
                continue

            log.info(
                "Processing analytics job jobId=%s "
                "recordingId=%s cameraId=%s",
                job.job_id,
                job.recording_id,
                job.camera_id,
            )

            active_realtime_jobs = (
                multistream.active_jobs
                if multistream is not None
                else len(realtime_by_camera)
            )
            if active_realtime_jobs > 0 or recording_task is not None:
                worker_events.publish_status(
                    job_id=job.job_id,
                    camera_id=job.camera_id,
                    recording_id=job.recording_id,
                    job_type=job.job_type,
                    status="REJECTED",
                    details={
                        "errorCode": "WORKER_BUSY",
                        "message": "Worker is already processing another analytics job",
                    },
                )
                consumer.commit(message=message, asynchronous=False)
                continue

            worker_events.publish_status(
                job_id=job.job_id,
                camera_id=job.camera_id,
                recording_id=job.recording_id,
                job_type=job.job_type,
                status="RUNNING",
            )
            heartbeat.set_active_jobs(len(realtime_by_camera) + 1)

            stop_event = threading.Event()
            recording_thread = threading.Thread(
                target=process_recording_job,
                args=(job, stop_event, worker_events),
                name=f"recording-{job.job_id}",
                daemon=True,
            )
            recording_task = RecordingTask(job, stop_event, recording_thread)
            recording_thread.start()

            consumer.commit(
                message=message,
                asynchronous=False,
            )

    finally:
        if recording_task is not None:
            recording_task.stop_event.set()
            recording_task.thread.join(timeout=20)
        for running in list(realtime_by_camera.values()):
            stop_realtime(running)
        if multistream is not None:
            multistream.close()
        heartbeat.stop()
        consumer.close()
        worker_events.close()


if __name__ == "__main__":
    main()
