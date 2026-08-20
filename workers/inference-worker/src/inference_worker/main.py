import json
import logging
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, TextIO
from uuid import NAMESPACE_URL, uuid5

import cv2
import torch
from ultralytics import YOLO

from inference_worker.event_producer import (
    AnalyticsEventProducer,
)
from inference_worker.snapshot_storage import (
    SnapshotStorage,
)


INPUT_FILE = Path(
    os.getenv(
        "ANALYTICS_INPUT_FILE",
        "data/analytics/input/people.mp4",
    )
)

OUTPUT_VIDEO_FILE = Path(
    os.getenv(
        "ANALYTICS_OUTPUT_VIDEO",
        "data/analytics/output/people-annotated.mp4",
    )
)

OUTPUT_EVENTS_FILE = Path(
    os.getenv(
        "ANALYTICS_OUTPUT_EVENTS",
        "data/analytics/output/analytics-events.jsonl",
    )
)

# В этой директории снимок хранится только временно,
# до успешной загрузки в S3.
SNAPSHOTS_DIRECTORY = Path(
    os.getenv(
        "ANALYTICS_SNAPSHOTS_DIRECTORY",
        "data/analytics/snapshots",
    )
)

SNAPSHOTS_PUBLIC_PATH = os.getenv(
    "ANALYTICS_SNAPSHOTS_PUBLIC_PATH",
    "/api/v1/analytics/snapshots",
).rstrip("/")

SNAPSHOT_JPEG_QUALITY = int(
    os.getenv(
        "ANALYTICS_SNAPSHOT_JPEG_QUALITY",
        "75",
    )
)

MODEL_FILE = os.getenv(
    "YOLO_MODEL",
    "yolo11n.pt",
)

CONFIDENCE = float(
    os.getenv(
        "YOLO_CONFIDENCE",
        "0.5",
    )
)

OBJECT_CLASSES = tuple(
    int(value.strip())
    for value in os.getenv(
        "YOLO_CLASSES",
        "0",
    ).split(",")
    if value.strip()
)

YOLO_DEVICE = os.getenv(
    "YOLO_DEVICE",
    "auto",
)

CAMERA_ID = os.getenv(
    "CAMERA_ID",
    "demo-camera-1",
)

LINE_POSITION = float(
    os.getenv(
        "LINE_POSITION",
        "0.5",
    )
)

CROSSING_COOLDOWN_SECONDS = float(
    os.getenv(
        "CROSSING_COOLDOWN_SECONDS",
        "2.0",
    )
)

KAFKA_BOOTSTRAP_SERVERS = os.getenv(
    "KAFKA_BOOTSTRAP_SERVERS",
    "localhost:9092",
)

KAFKA_TOPIC = os.getenv(
    "KAFKA_TOPIC",
    "analytics.events",
)

KAFKA_ENABLED = os.getenv(
    "KAFKA_ENABLED",
    "true",
).lower() in {
    "true",
    "1",
    "yes",
}

RECORDING_ID = os.getenv(
    "RECORDING_ID",
)

PROGRESS_FILE = (
    Path(os.environ["ANALYTICS_PROGRESS_FILE"])
    if os.getenv("ANALYTICS_PROGRESS_FILE")
    else None
)


def write_progress(processed_frames: int, total_frames: int) -> None:
    """Atomically expose progress to the supervising recording consumer."""
    if PROGRESS_FILE is None:
        return
    progress = {
        "processedFrames": processed_frames,
        "totalFrames": total_frames,
        "progressPercent": (
            min(100, round(processed_frames * 100 / total_frames))
            if total_frames > 0
            else None
        ),
    }
    temporary = PROGRESS_FILE.with_suffix(".tmp")
    temporary.write_text(json.dumps(progress), encoding="utf-8")
    temporary.replace(PROGRESS_FILE)


def resolve_device() -> str:
    """
    Возвращает устройство для Ultralytics.

    YOLO_DEVICE может иметь значения:
    - auto
    - cpu
    - cuda:0
    - cuda:1
    - 0
    - 1
    """
    configured_device = (
        YOLO_DEVICE.strip().lower()
    )

    if configured_device != "auto":
        return configured_device

    if torch.cuda.is_available():
        return "cuda:0"

    return "cpu"


def is_cuda_device(
    device: str,
) -> bool:
    return (
        device.startswith("cuda")
        or device.isdigit()
    )


def get_cuda_device_index(
    device: str,
) -> int:
    if device.isdigit():
        return int(device)

    if ":" in device:
        return int(
            device.split(
                ":",
                maxsplit=1,
            )[1]
        )

    return 0


def log_inference_device(
    device: str,
) -> None:
    cuda_available = (
        torch.cuda.is_available()
    )

    if (
        is_cuda_device(device)
        and not cuda_available
    ):
        raise RuntimeError(
            "CUDA device was requested "
            f"({device}), but CUDA is not available. "
            "Check the NVIDIA Windows driver, WSL2 "
            "configuration and CUDA-enabled PyTorch."
        )

    if is_cuda_device(device):
        device_index = (
            get_cuda_device_index(
                device
            )
        )

        device_count = (
            torch.cuda.device_count()
        )

        if device_index >= device_count:
            raise RuntimeError(
                "Requested CUDA device index "
                f"{device_index}, but only "
                f"{device_count} CUDA device(s) "
                "are available"
            )

        gpu_name = (
            torch.cuda.get_device_name(
                device_index
            )
        )
    else:
        gpu_name = "none"

    logging.info(
        "YOLO device=%s "
        "cudaAvailable=%s "
        "cudaDeviceCount=%s "
        "gpu=%s",
        device,
        cuda_available,
        torch.cuda.device_count(),
        gpu_name,
    )


def create_line_crossing_event(
    track_id: int,
    direction: str,
    confidence: float,
    frame_number: int,
    video_time_seconds: float,
    recording_id: str,
) -> dict[str, Any]:
    """
    Создаёт событие пересечения линии.

    UUID генерируется детерминированно.
    При повторной обработке той же записи,
    трека и кадра получится тот же eventId.
    """
    event_id = uuid5(
        NAMESPACE_URL,
        (
            f"{recording_id}:LINE_CROSSED:"
            f"{track_id}:{frame_number}"
        ),
    )

    return {
        "eventId": str(event_id),
        "schemaVersion": 1,
        "eventType": "LINE_CROSSED",
        "cameraId": CAMERA_ID,
        "trackId": track_id,
        "recordingId": recording_id,
        "objectType": "PERSON",
        "confidence": round(
            confidence,
            4,
        ),
        "frameNumber": frame_number,
        "videoTimeSeconds": round(
            video_time_seconds,
            3,
        ),
        "occurredAt": datetime.now(
            timezone.utc
        ).isoformat(),
        "attributes": {
            "direction": direction,
            "lineId": "main-line",
        },
    }


def write_event(
    event: dict[str, Any],
    events_file: TextIO,
) -> None:
    """
    Записывает событие в локальный JSONL-файл.

    Файл находится во временной рабочей директории
    задачи и используется для диагностики.
    """
    json_line = json.dumps(
        event,
        ensure_ascii=False,
    )

    events_file.write(
        json_line + "\n"
    )

    events_file.flush()

    logging.info(
        "Analytics event: %s",
        json_line,
    )


def create_event_producer() -> (
    AnalyticsEventProducer | None
):
    if not KAFKA_ENABLED:
        logging.info(
            "Kafka publishing disabled"
        )
        return None

    logging.info(
        "Kafka enabled: "
        "bootstrapServers=%s topic=%s",
        KAFKA_BOOTSTRAP_SERVERS,
        KAFKA_TOPIC,
    )

    return AnalyticsEventProducer(
        bootstrap_servers=(
            KAFKA_BOOTSTRAP_SERVERS
        ),
        topic=KAFKA_TOPIC,
    )


def validate_configuration() -> None:
    if not OBJECT_CLASSES:
        raise ValueError(
            "YOLO_CLASSES must contain at least one class id"
        )

    if not 0.0 < LINE_POSITION < 1.0:
        raise ValueError(
            "LINE_POSITION must be between 0 and 1"
        )

    if not 0.0 <= CONFIDENCE <= 1.0:
        raise ValueError(
            "YOLO_CONFIDENCE must be between 0 and 1"
        )

    if not 1 <= SNAPSHOT_JPEG_QUALITY <= 100:
        raise ValueError(
            "ANALYTICS_SNAPSHOT_JPEG_QUALITY "
            "must be between 1 and 100"
        )

    if not RECORDING_ID:
        raise ValueError(
            "RECORDING_ID environment variable "
            "is required"
        )

    if not INPUT_FILE.is_file():
        raise FileNotFoundError(
            "Video not found: "
            f"{INPUT_FILE.resolve()}"
        )


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format=(
            "%(asctime)s %(levelname)s "
            "%(name)s - %(message)s"
        ),
    )

    validate_configuration()

    device = resolve_device()

    log_inference_device(
        device
    )

    OUTPUT_VIDEO_FILE.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    OUTPUT_EVENTS_FILE.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    SNAPSHOTS_DIRECTORY.mkdir(
        parents=True,
        exist_ok=True,
    )

    logging.info(
        "Loading model: %s classes=%s",
        MODEL_FILE,
        OBJECT_CLASSES,
    )

    model = YOLO(
        MODEL_FILE
    )

    capture = cv2.VideoCapture(
        str(INPUT_FILE)
    )

    if not capture.isOpened():
        raise RuntimeError(
            "Cannot open video: "
            f"{INPUT_FILE.resolve()}"
        )

    fps = capture.get(
        cv2.CAP_PROP_FPS
    )

    if fps <= 0:
        fps = 25.0

    total_frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    write_progress(0, total_frames)

    width = int(
        capture.get(
            cv2.CAP_PROP_FRAME_WIDTH
        )
    )

    height = int(
        capture.get(
            cv2.CAP_PROP_FRAME_HEIGHT
        )
    )

    if width <= 0 or height <= 0:
        capture.release()

        raise RuntimeError(
            "Input video has invalid dimensions: "
            f"{width}x{height}"
        )

    writer = cv2.VideoWriter(
        str(OUTPUT_VIDEO_FILE),
        cv2.VideoWriter_fourcc(
            *"mp4v"
        ),
        fps,
        (
            width,
            height,
        ),
    )

    if not writer.isOpened():
        capture.release()

        raise RuntimeError(
            "Cannot create output video: "
            f"{OUTPUT_VIDEO_FILE.resolve()}"
        )

    line_y = int(
        height * LINE_POSITION
    )

    crossing_cooldown_frames = max(
        1,
        int(
            fps
            * CROSSING_COOLDOWN_SECONDS
        ),
    )

    previous_y_by_track: dict[
        int,
        int,
    ] = {}

    last_crossing_frame_by_track: dict[
        int,
        int,
    ] = {}

    frame_number = 0
    crossing_count = 0

    event_producer = (
        create_event_producer()
    )

    snapshot_storage = (
        SnapshotStorage()
    )

    events_file = (
        OUTPUT_EVENTS_FILE.open(
            mode="w",
            encoding="utf-8",
        )
    )

    logging.info(
        "Processing video=%s "
        "fps=%.2f size=%sx%s "
        "lineY=%s device=%s",
        INPUT_FILE.resolve(),
        fps,
        width,
        height,
        line_y,
        device,
    )

    try:
        while True:
            success, frame = (
                capture.read()
            )

            if not success:
                break

            frame_number += 1

            if frame_number == 1 or frame_number % 25 == 0:
                write_progress(frame_number, total_frames)

            results = model.track(
                frame,
                persist=True,
                tracker="bytetrack.yaml",
                classes=list(OBJECT_CLASSES),
                conf=CONFIDENCE,
                device=device,
                verbose=False,
            )

            result = results[0]

            annotated_frame = (
                result.plot()
            )

            cv2.line(
                annotated_frame,
                (
                    0,
                    line_y,
                ),
                (
                    width,
                    line_y,
                ),
                (
                    0,
                    0,
                    255,
                ),
                2,
            )

            cv2.putText(
                annotated_frame,
                "COUNTING LINE",
                (
                    20,
                    max(
                        30,
                        line_y - 10,
                    ),
                ),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.7,
                (
                    0,
                    0,
                    255,
                ),
                2,
                cv2.LINE_AA,
            )

            boxes = result.boxes

            if (
                boxes is not None
                and boxes.id is not None
                and boxes.conf is not None
            ):
                track_ids = (
                    boxes.id
                    .int()
                    .cpu()
                    .tolist()
                )

                coordinates = (
                    boxes.xyxy
                    .int()
                    .cpu()
                    .tolist()
                )

                confidences = (
                    boxes.conf
                    .cpu()
                    .tolist()
                )

                for (
                    track_id,
                    coordinates_for_track,
                    confidence,
                ) in zip(
                    track_ids,
                    coordinates,
                    confidences,
                ):
                    (
                        x1,
                        _y1,
                        x2,
                        y2,
                    ) = coordinates_for_track

                    # Нижняя центральная точка рамки:
                    # приблизительное положение ног.
                    point_x = (
                        x1 + x2
                    ) // 2

                    point_y = y2

                    previous_y = (
                        previous_y_by_track.get(
                            track_id
                        )
                    )

                    if previous_y is not None:
                        crossed_down = (
                            previous_y
                            < line_y
                            <= point_y
                        )

                        crossed_up = (
                            previous_y
                            > line_y
                            >= point_y
                        )

                        last_crossing_frame = (
                            last_crossing_frame_by_track.get(
                                track_id,
                                -crossing_cooldown_frames,
                            )
                        )

                        cooldown_finished = (
                            frame_number
                            - last_crossing_frame
                            >= crossing_cooldown_frames
                        )

                        if (
                            (
                                crossed_down
                                or crossed_up
                            )
                            and cooldown_finished
                        ):
                            direction = (
                                "DOWN"
                                if crossed_down
                                else "UP"
                            )

                            video_time_seconds = (
                                frame_number
                                / fps
                            )

                            event = (
                                create_line_crossing_event(
                                    track_id=track_id,
                                    direction=direction,
                                    confidence=confidence,
                                    frame_number=(
                                        frame_number
                                    ),
                                    video_time_seconds=(
                                        video_time_seconds
                                    ),
                                    recording_id=(
                                        RECORDING_ID
                                    ),
                                )
                            )

                            snapshot_name = (
                                f"{event['eventId']}.jpg"
                            )

                            snapshot_file = (
                                SNAPSHOTS_DIRECTORY
                                / snapshot_name
                            )

                            snapshot_saved = cv2.imwrite(
                                str(snapshot_file),
                                annotated_frame,
                                [
                                    cv2.IMWRITE_JPEG_QUALITY,
                                    SNAPSHOT_JPEG_QUALITY,
                                ],
                            )

                            if not snapshot_saved:
                                raise RuntimeError(
                                    "Cannot save snapshot: "
                                    f"{snapshot_file.resolve()}"
                                )

                            snapshot_key = (
                                snapshot_storage.upload(
                                    event["eventId"],
                                    snapshot_file,
                                )
                            )

                            snapshot_file.unlink(
                                missing_ok=True
                            )

                            event["attributes"][
                                "snapshotUrl"
                            ] = (
                                f"{SNAPSHOTS_PUBLIC_PATH}/"
                                f"{snapshot_name}"
                            )

                            event["attributes"][
                                "snapshotKey"
                            ] = snapshot_key

                            write_event(
                                event,
                                events_file,
                            )

                            if (
                                event_producer
                                is not None
                            ):
                                event_producer.publish(
                                    event
                                )

                            last_crossing_frame_by_track[
                                track_id
                            ] = frame_number

                            crossing_count += 1

                    previous_y_by_track[
                        track_id
                    ] = point_y

                    cv2.circle(
                        annotated_frame,
                        (
                            point_x,
                            point_y,
                        ),
                        5,
                        (
                            255,
                            0,
                            0,
                        ),
                        -1,
                    )

            cv2.putText(
                annotated_frame,
                f"Crossed: {crossing_count}",
                (
                    20,
                    40,
                ),
                cv2.FONT_HERSHEY_SIMPLEX,
                1.0,
                (
                    0,
                    255,
                    0,
                ),
                2,
                cv2.LINE_AA,
            )

            writer.write(
                annotated_frame
            )

            if frame_number % 100 == 0:
                logging.info(
                    "Processed frames=%s "
                    "crossings=%s",
                    frame_number,
                    crossing_count,
                )

    finally:
        if event_producer is not None:
            event_producer.close()

        events_file.close()
        capture.release()
        writer.release()

    logging.info(
        "Processing completed "
        "frames=%s crossings=%s",
        frame_number,
        crossing_count,
    )
    write_progress(frame_number, total_frames)

    logging.info(
        "Video result: %s",
        OUTPUT_VIDEO_FILE.resolve(),
    )

    logging.info(
        "Events result: %s",
        OUTPUT_EVENTS_FILE.resolve(),
    )


if __name__ == "__main__":
    main()
