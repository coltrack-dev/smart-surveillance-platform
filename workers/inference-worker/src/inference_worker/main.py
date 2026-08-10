from pathlib import Path

import cv2
from ultralytics import YOLO


INPUT_FILE = Path("data/analytics/input/people.mp4")
OUTPUT_FILE = Path(
    "data/analytics/output/people-annotated.mp4"
)

MODEL_FILE = "yolo11n.pt"
CONFIDENCE = 0.5


def main() -> None:
    if not INPUT_FILE.is_file():
        raise FileNotFoundError(
            f"Video not found: {INPUT_FILE.resolve()}"
        )

    OUTPUT_FILE.parent.mkdir(
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
        str(OUTPUT_FILE),
        cv2.VideoWriter_fourcc(*"mp4v"),
        fps,
        (width, height),
    )

    if not writer.isOpened():
        capture.release()
        raise RuntimeError(
            f"Cannot create output video: "
            f"{OUTPUT_FILE.resolve()}"
        )

    # Горизонтальная виртуальная линия по центру кадра.
    line_y = height // 2

    # Последняя позиция каждого объекта относительно линии.
    previous_y_by_track: dict[int, int] = {}

    # Не позволяет создавать несколько событий для одного trackId.
    crossed_track_ids: set[int] = set()

    frame_number = 0

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
                classes=[0],  # Только person.
                conf=CONFIDENCE,
                verbose=False,
            )

            result = results[0]
            annotated_frame = result.plot()

            # Рисуем виртуальную линию.
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

                for track_id, coordinates_for_track in zip(
                    track_ids,
                    coordinates,
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

                        if (
                            (crossed_down or crossed_up)
                            and track_id
                            not in crossed_track_ids
                        ):
                            direction = (
                                "DOWN"
                                if crossed_down
                                else "UP"
                            )

                            timestamp_seconds = (
                                frame_number / fps
                            )

                            print(
                                "LINE_CROSSED "
                                f"trackId={track_id} "
                                f"direction={direction} "
                                f"frame={frame_number} "
                                f"time={timestamp_seconds:.2f}s"
                            )

                            crossed_track_ids.add(track_id)

                    previous_y_by_track[track_id] = point_y

                    # Точка, используемая для проверки пересечения.
                    cv2.circle(
                        annotated_frame,
                        (point_x, point_y),
                        5,
                        (255, 0, 0),
                        -1,
                    )

            # Счётчик уникальных пересечений.
            cv2.putText(
                annotated_frame,
                f"Crossed: {len(crossed_track_ids)}",
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
        capture.release()
        writer.release()

    print(f"Processed frames: {frame_number}")
    print(
        f"Unique line crossings: "
        f"{len(crossed_track_ids)}"
    )
    print(f"Result: {OUTPUT_FILE.resolve()}")


if __name__ == "__main__":
    main()
