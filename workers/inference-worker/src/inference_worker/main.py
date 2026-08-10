import json
import logging
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, TextIO
from uuid import uuid4

import cv2
from ultralytics import YOLO

from inference_worker.event_producer import (
    AnalyticsEventProducer,
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


def create_line_crossing_event(
    track_id: int,
    direction: str,
    confidence: float,
    frame_number: int,
    video_time_seconds: float,
) -> dict[str, Any]:
    return {
        "eventId": str(uuid4()),
        "eventType": "LINE_CROSSED",
        "cameraId": CAMERA_ID,
        "trackId": track_id,
        "objectType": "PERSON",
        "direction": direction,
        "confidence": round(confidence, 4),
        "frameNumber": frame_number,
        "videoTimeSeconds": round(
            video_time_seconds,
            3,
        ),
        "occurredAt": datetime.now(
            timezone.utc
        ).isoformat(),
    }


def write_event(
    event: dict[str, Any],
    events_file: TextIO,
) -> None:
    json_line = json.dumps(
        event,
        ensure_ascii=False,
    )

    events_file.write(json_line + "\n")
    events_file.flush()

    logging.info(
        "Analytics event: %s",
        json_line,
    )


def create_event_producer() -> (
    AnalyticsEventProducer | None
):
    if not KAFKA_ENABLED:
        logging.info("Kafka publishing disabled")
        return None

    logging.info(
        "Kafka enabled: bootstrapServers=%s topic=%s",
        KAFKA_BOOTSTRAP_SERVERS,
        KAFKA_TOPIC,
    )

    return AnalyticsEventProducer(
        bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
        topic=KAFKA_TOPIC,
    )


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format=(
            "%(asctime)s %(levelname)s "
            "%(name)s - %(message)s"
        ),
    )

    if not 0.0 < LINE_POSITION < 1.0:
        raise ValueError(
            "LINE_POSITION must be between 0 and 1"
        )

    if not 0.0 <= CONFIDENCE <= 1.0:
        raise ValueError(
            "YOLO_CONFIDENCE must be between 0 and 1"
        )

    if not INPUT_FILE.is_file():
        raise FileNotFoundError(
            f"Video not found: {INPUT_FILE.resolve()}"
        )

    OUTPUT_VIDEO_FILE.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    OUTPUT_EVENTS_FILE.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    logging.info(
        "Loading model: %s",
        MODEL_FILE,
    )

    model = YOLO(MODEL_FILE)

    capture = cv2.VideoCapture(
        str(INPUT_FILE)
    )

    if not capture.isOpened():
        raise RuntimeError(
            f"Cannot open video: {INPUT_FILE.resolve()}"
        )

    fps = capture.get(
        cv2.CAP_PROP_FPS
    )

    if fps <= 0:
        fps = 25.0

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
        cv2.VideoWriter_fourcc(*"mp4v"),
        fps,
        (width, height),
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
            fps * CROSSING_COOLDOWN_SECONDS
        ),
    )

    previous_y_by_track: dict[int, int] = {}

    last_crossing_frame_by_track: dict[
        int,
        int,
    ] = {}

    frame_number = 0
    crossing_count = 0

    event_producer = create_event_producer()

    events_file = OUTPUT_EVENTS_FILE.open(
        mode="w",
        encoding="utf-8",
    )

    logging.info(
        "Processing video=%s fps=%.2f size=%sx%s lineY=%s",
        INPUT_FILE.resolve(),
        fps,
        width,
        height,
        line_y,
    )

    try:
        while True:
            success, frame = capture.read()

            if not success:
                break

            frame_number += 1

            results = model.track(
                frame,
                persist=True,
                tracker="bytetrack.yaml",
                classes=[0],
                conf=CONFIDENCE,
                verbose=False,
            )

            result = results[0]
            annotated_frame = result.plot()

            cv2.line(
                annotated_frame,
                (0, line_y),
                (width, line_y),
                (0, 0, 255),
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
                (0, 0, 255),
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
                    x1, _y1, x2, y2 = (
                        coordinates_for_track
                    )

                    # Нижняя центральная точка рамки:
                    # приблизительное положение ног.
                    point_x = (x1 + x2) // 2
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
                                frame_number / fps
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
                                )
                            )

                            # Сначала сохраняем локально.
                            write_event(
                                event,
                                events_file,
                            )

                            # Затем публикуем в Kafka.
                            if event_producer is not None:
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
                        (point_x, point_y),
                        5,
                        (255, 0, 0),
                        -1,
                    )

            cv2.putText(
                annotated_frame,
                f"Crossed: {crossing_count}",
                (20, 40),
                cv2.FONT_HERSHEY_SIMPLEX,
                1.0,
                (0, 255, 0),
                2,
                cv2.LINE_AA,
            )

            writer.write(
                annotated_frame
            )

            if frame_number % 100 == 0:
                logging.info(
                    "Processed frames=%s crossings=%s",
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
        "Processing completed frames=%s crossings=%s",
        frame_number,
        crossing_count,
    )

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
