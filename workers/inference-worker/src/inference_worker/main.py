import json
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

import cv2
from ultralytics import YOLO


INPUT_FILE = Path("data/analytics/input/people.mp4")
OUTPUT_VIDEO_FILE = Path(
    "data/analytics/output/people-annotated.mp4"
)
OUTPUT_EVENTS_FILE = Path(
    "data/analytics/output/analytics-events.jsonl"
)

MODEL_FILE = "yolo11n.pt"
CONFIDENCE = 0.5

CAMERA_ID = "demo-camera-1"

# Повторное событие для одного trackId разрешается через 2 секунды.
CROSSING_COOLDOWN_SECONDS = 2.0


def create_line_crossing_event(
    track_id: int,
    direction: str,
    confidence: float,
    frame_number: int,
    video_time_seconds: float,
) -> dict:
    return {
        "eventId": str(uuid4()),
        "eventType": "LINE_CROSSED",
        "cameraId": CAMERA_ID,
        "trackId": track_id,
        "objectType": "PERSON",
        "direction": direction,
        "confidence": round(confidence, 4),
        "frameNumber": frame_number,
        "videoTimeSeconds": round(video_time_seconds, 3),
        "occurredAt": datetime.now(timezone.utc).isoformat(),
    }


def write_event(event: dict, events_file) -> None:
    json_line = json.dumps(
        event,
        ensure_ascii=False,
    )

    events_file.write(json_line + "\n")
    events_file.flush()

    print(json_line)


def main() -> None:
    if not INPUT_FILE.is_file():
        raise FileNotFoundError(
            f"Video not found: {INPUT_FILE.resolve()}"
        )

    OUTPUT_VIDEO_FILE.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    model = YOLO(MODEL_FILE)
    capture = cv2.VideoCapture(str(INPUT_FILE))

    if not capture.isOpened():
        raise RuntimeError(
            f"Cannot open video: {INPUT_FILE.resolve()}"
        )

    fps = capture.get(cv2.CAP_PROP_FPS)

    if fps <= 0:
        fps = 25.0

    width = int(
        capture.get(cv2.CAP_PROP_FRAME_WIDTH)
    )
    height = int(
        capture.get(cv2.CAP_PROP_FRAME_HEIGHT)
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

    line_y = height // 2

    previous_y_by_track: dict[int, int] = {}

    last_crossing_frame_by_track: dict[int, int] = {}

    crossing_cooldown_frames = int(
        fps * CROSSING_COOLDOWN_SECONDS
    )

    frame_number = 0
    crossing_count = 0

    # Перезаписываем файл событий при каждом новом запуске.
    events_file = OUTPUT_EVENTS_FILE.open(
        mode="w",
        encoding="utf-8",
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
                (20, max(30, line_y - 10)),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.7,
                (0, 0, 255),
                2,
                cv2.LINE_AA,
            )

            boxes = result.boxes

            if boxes is not None and boxes.id is not None:
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
                    x1, y1, x2, y2 = coordinates_for_track

                    # Нижняя центральная точка рамки —
                    # приблизительное положение ног человека.
                    point_x = (x1 + x2) // 2
                    point_y = y2

                    previous_y = previous_y_by_track.get(
                        track_id
                    )

                    if previous_y is not None:
                        crossed_down = (
                            previous_y < line_y <= point_y
                        )

                        crossed_up = (
                            previous_y > line_y >= point_y
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
                            (crossed_down or crossed_up)
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

                            event = create_line_crossing_event(
                                track_id=track_id,
                                direction=direction,
                                confidence=confidence,
                                frame_number=frame_number,
                                video_time_seconds=(
                                    video_time_seconds
                                ),
                            )

                            write_event(
                                event,
                                events_file,
                            )

                            last_crossing_frame_by_track[
                                track_id
                            ] = frame_number

                            crossing_count += 1

                    previous_y_by_track[track_id] = point_y

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

            writer.write(annotated_frame)

            if frame_number % 100 == 0:
                print(
                    f"Processed frames: {frame_number}"
                )

    finally:
        events_file.close()
        capture.release()
        writer.release()

    print(f"Processed frames: {frame_number}")
    print(f"Line crossings: {crossing_count}")
    print(
        f"Video result: {OUTPUT_VIDEO_FILE.resolve()}"
    )
    print(
        f"Events result: {OUTPUT_EVENTS_FILE.resolve()}"
    )


if __name__ == "__main__":
    main()
