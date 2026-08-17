from __future__ import annotations

import logging
import os
import signal
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from threading import Event
from uuid import uuid4

import cv2
import torch
from ultralytics import YOLO

from inference_worker.event_producer import AnalyticsEventProducer
from inference_worker.snapshot_storage import SnapshotStorage


log = logging.getLogger(__name__)
stop_requested = Event()


def env_float(name: str, default: float) -> float:
    return float(os.getenv(name, str(default)))


def resolve_device() -> str:
    configured = os.getenv("YOLO_DEVICE", "auto").strip().lower()
    if configured != "auto":
        return configured
    return "cuda:0" if torch.cuda.is_available() else "cpu"


def request_stop(_signum, _frame) -> None:
    stop_requested.set()


def create_event(
    *,
    camera_id: str,
    job_id: str,
    track_id: int,
    direction: str,
    confidence: float,
    frame_number: int,
    stream_time_seconds: float,
) -> dict:
    return {
        "eventId": str(uuid4()),
        "schemaVersion": 1,
        "eventType": "LINE_CROSSED",
        "cameraId": camera_id,
        "recordingId": None,
        "trackId": track_id,
        "objectType": "PERSON",
        "confidence": round(confidence, 4),
        "frameNumber": frame_number,
        "videoTimeSeconds": round(stream_time_seconds, 3),
        "occurredAt": datetime.now(timezone.utc).isoformat(),
        "attributes": {
            "direction": direction,
            "lineId": "main-line",
            "source": "REALTIME",
            "jobId": job_id,
        },
    }


def open_stream(url: str, transport: str) -> cv2.VideoCapture:
    os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = (
        f"rtsp_transport;{transport}|stimeout;15000000"
    )
    capture = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
    capture.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    return capture


def run_stream() -> None:
    url = os.environ["ANALYTICS_RTSP_URL"]
    camera_id = os.environ["CAMERA_ID"]
    job_id = os.environ["ANALYTICS_JOB_ID"]
    transport = os.getenv("RTSP_TRANSPORT", "tcp").lower()
    model_file = os.getenv("YOLO_MODEL", "yolo11n.pt")
    confidence = env_float("YOLO_CONFIDENCE", 0.5)
    line_position = env_float("LINE_POSITION", 0.5)
    target_fps = env_float("ANALYTICS_TARGET_FPS", 10.0)
    reconnect_seconds = env_float("RTSP_RECONNECT_SECONDS", 5.0)
    cooldown_seconds = env_float("CROSSING_COOLDOWN_SECONDS", 2.0)
    jpeg_quality = int(os.getenv("ANALYTICS_SNAPSHOT_JPEG_QUALITY", "75"))
    classes = tuple(
        int(value.strip())
        for value in os.getenv("YOLO_CLASSES", "0").split(",")
        if value.strip()
    )

    if transport not in {"tcp", "udp"}:
        raise ValueError("RTSP_TRANSPORT must be tcp or udp")
    if not classes:
        raise ValueError("YOLO_CLASSES must not be empty")
    if target_fps <= 0:
        raise ValueError("ANALYTICS_TARGET_FPS must be positive")
    if not 0 < line_position < 1:
        raise ValueError("LINE_POSITION must be between 0 and 1")

    device = resolve_device()
    if (device.startswith("cuda") or device.isdigit()) and not torch.cuda.is_available():
        raise RuntimeError(f"CUDA requested ({device}), but CUDA is unavailable")

    gpu_name = torch.cuda.get_device_name(0) if torch.cuda.is_available() else "none"
    log.info(
        "Starting realtime inference jobId=%s cameraId=%s url=%s "
        "device=%s gpu=%s targetFps=%.2f classes=%s",
        job_id,
        camera_id,
        url,
        device,
        gpu_name,
        target_fps,
        classes,
    )

    model = YOLO(model_file)
    producer = AnalyticsEventProducer(
        bootstrap_servers=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
        topic=os.getenv("KAFKA_TOPIC", "analytics.events"),
    )
    snapshot_storage = SnapshotStorage()
    snapshot_directory = Path(
        os.getenv("ANALYTICS_SNAPSHOTS_DIRECTORY", "/tmp/analytics-snapshots")
    )
    snapshot_directory.mkdir(parents=True, exist_ok=True)
    public_path = os.getenv(
        "ANALYTICS_SNAPSHOTS_PUBLIC_PATH", "/api/v1/analytics/snapshots"
    ).rstrip("/")

    previous_y_by_track: dict[int, int] = {}
    last_crossing_at_by_track: dict[int, float] = {}
    frame_number = 0
    processed_frames = 0
    crossings = 0
    started_at = time.monotonic()
    next_frame_at = started_at
    capture: cv2.VideoCapture | None = None

    try:
        while not stop_requested.is_set():
            if capture is None or not capture.isOpened():
                if capture is not None:
                    capture.release()
                log.info("Connecting RTSP url=%s transport=%s", url, transport)
                capture = open_stream(url, transport)
                if not capture.isOpened():
                    log.warning("RTSP connection failed; retrying in %.1fs", reconnect_seconds)
                    stop_requested.wait(reconnect_seconds)
                    continue
                previous_y_by_track.clear()
                log.info("RTSP connected url=%s", url)

            success, frame = capture.read()
            if not success:
                log.warning("RTSP read failed; reconnecting in %.1fs", reconnect_seconds)
                capture.release()
                capture = None
                stop_requested.wait(reconnect_seconds)
                continue

            frame_number += 1
            now = time.monotonic()
            if now < next_frame_at:
                continue
            next_frame_at = now + (1.0 / target_fps)
            processed_frames += 1

            height, width = frame.shape[:2]
            line_y = int(height * line_position)
            results = model.track(
                frame,
                persist=True,
                tracker="bytetrack.yaml",
                classes=list(classes),
                conf=confidence,
                device=device,
                verbose=False,
            )
            result = results[0]
            boxes = result.boxes
            if boxes is None or boxes.id is None or boxes.conf is None:
                continue

            track_ids = boxes.id.int().cpu().tolist()
            coordinates = boxes.xyxy.int().cpu().tolist()
            confidences = boxes.conf.cpu().tolist()

            for track_id, coordinates_for_track, detected_confidence in zip(
                track_ids, coordinates, confidences
            ):
                x1, _y1, x2, y2 = coordinates_for_track
                point_y = y2
                previous_y = previous_y_by_track.get(track_id)
                previous_y_by_track[track_id] = point_y
                if previous_y is None:
                    continue

                crossed_down = previous_y < line_y <= point_y
                crossed_up = previous_y > line_y >= point_y
                last_crossing_at = last_crossing_at_by_track.get(track_id, 0.0)
                if not (crossed_down or crossed_up) or now - last_crossing_at < cooldown_seconds:
                    continue

                event = create_event(
                    camera_id=camera_id,
                    job_id=job_id,
                    track_id=track_id,
                    direction="DOWN" if crossed_down else "UP",
                    confidence=detected_confidence,
                    frame_number=frame_number,
                    stream_time_seconds=now - started_at,
                )
                annotated = result.plot()
                cv2.line(annotated, (0, line_y), (width, line_y), (0, 0, 255), 2)

                with tempfile.NamedTemporaryFile(
                    prefix=f"{event['eventId']}-",
                    suffix=".jpg",
                    dir=snapshot_directory,
                    delete=False,
                ) as temporary_snapshot:
                    snapshot_path = Path(temporary_snapshot.name)
                try:
                    if not cv2.imwrite(
                        str(snapshot_path),
                        annotated,
                        [cv2.IMWRITE_JPEG_QUALITY, jpeg_quality],
                    ):
                        raise RuntimeError(f"Cannot save snapshot: {snapshot_path}")
                    snapshot_key = snapshot_storage.upload(event["eventId"], snapshot_path)
                finally:
                    snapshot_path.unlink(missing_ok=True)

                event["attributes"]["snapshotKey"] = snapshot_key
                event["attributes"]["snapshotUrl"] = (
                    f"{public_path}/{event['eventId']}.jpg"
                )
                producer.publish(event)
                crossings += 1
                last_crossing_at_by_track[track_id] = now
                log.info(
                    "Realtime crossing eventId=%s trackId=%s direction=%s crossings=%s",
                    event["eventId"],
                    track_id,
                    event["attributes"]["direction"],
                    crossings,
                )

            if processed_frames % 100 == 0:
                log.info(
                    "Realtime processedFrames=%s sourceFrames=%s crossings=%s",
                    processed_frames,
                    frame_number,
                    crossings,
                )
    finally:
        if capture is not None:
            capture.release()
        producer.close()

    log.info(
        "Realtime inference stopped jobId=%s processedFrames=%s crossings=%s",
        job_id,
        processed_frames,
        crossings,
    )


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )
    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    run_stream()


if __name__ == "__main__":
    main()
