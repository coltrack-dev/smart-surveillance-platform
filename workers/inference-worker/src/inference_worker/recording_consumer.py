import json
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import time

from dataclasses import dataclass
from pathlib import Path
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
) -> None:
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
            }
        )

        environment["YOLO_CLASSES"] = ",".join(
            str(value)
            for value in job.profile.classes
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

        subprocess.run(
            [
                sys.executable,
                "-m",
                "inference_worker.main",
            ],
            env=environment,
            check=True,
        )


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
        "Waiting for analytics jobs topics=%s workerId=%s",
        topics,
        worker_events.worker_id,
    )

    try:

        while True:

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

                if job.action == "STOP":
                    if running is None:
                        worker_events.publish_status(
                            job_id=job.job_id,
                            camera_id=job.camera_id,
                            recording_id=None,
                            job_type="REALTIME",
                            status="REJECTED",
                            details={"errorCode": "JOB_NOT_RUNNING"},
                        )
                    else:
                        stop_realtime(running)
                        realtime_by_camera.pop(job.camera_id, None)
                        heartbeat.set_active_jobs(len(realtime_by_camera))
                        worker_events.publish_status(
                            job_id=running.job.job_id,
                            camera_id=running.job.camera_id,
                            recording_id=None,
                            job_type="REALTIME",
                            status="STOPPED",
                            details={"stoppedByJobId": job.job_id},
                        )
                    consumer.commit(message=message, asynchronous=False)
                    continue

                if running is not None:
                    status = (
                        "RUNNING"
                        if running.job.job_id == job.job_id
                        else "REJECTED"
                    )
                    details = (
                        {"idempotent": True}
                        if status == "RUNNING"
                        else {
                            "errorCode": "CAMERA_ALREADY_RUNNING",
                            "runningJobId": running.job.job_id,
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

                if len(realtime_by_camera) >= heartbeat.max_jobs:
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
                    realtime_by_camera[job.camera_id] = running
                    heartbeat.set_active_jobs(len(realtime_by_camera))
                    worker_events.publish_status(
                        job_id=job.job_id,
                        camera_id=job.camera_id,
                        recording_id=None,
                        job_type="REALTIME",
                        status="RUNNING",
                        details={"pid": running.process.pid},
                    )
                    log.info(
                        "Realtime inference started jobId=%s cameraId=%s pid=%s",
                        job.job_id,
                        job.camera_id,
                        running.process.pid,
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

            if len(realtime_by_camera) >= heartbeat.max_jobs:
                worker_events.publish_status(
                    job_id=job.job_id,
                    camera_id=job.camera_id,
                    recording_id=job.recording_id,
                    job_type=job.job_type,
                    status="REJECTED",
                    details={"errorCode": "WORKER_BUSY"},
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

            retry_delay = 10

            while True:

                try:

                    run_inference(
                        job
                    )

                    break

                except Exception:

                    log.exception(
                        "Inference failed "
                        "jobId=%s recordingId=%s; "
                        "retrying in %ss",
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
                        details={
                            "retryDelaySeconds": retry_delay,
                        },
                    )

                    time.sleep(
                        retry_delay
                    )

                    retry_delay = min(
                        retry_delay * 2,
                        300,
                    )

            consumer.commit(
                message=message,
                asynchronous=False,
            )

            heartbeat.set_active_jobs(len(realtime_by_camera))

            worker_events.publish_status(
                job_id=job.job_id,
                camera_id=job.camera_id,
                recording_id=job.recording_id,
                job_type=job.job_type,
                status="COMPLETED",
            )

            log.info(
                "Inference completed jobId=%s recordingId=%s",
                job.job_id,
                job.recording_id,
            )

    finally:
        for running in list(realtime_by_camera.values()):
            stop_realtime(running)
        heartbeat.stop()
        consumer.close()
        worker_events.close()


if __name__ == "__main__":
    main()
