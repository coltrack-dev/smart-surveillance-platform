from pathlib import Path

import cv2
from ultralytics import YOLO


def main() -> None:
    input_file = Path(
        "./data/analytics/input/people.mp4"
    )
    output_file = Path(
        "./data/analytics/output/people-annotated.mp4"
    )

    if not input_file.is_file():
        raise FileNotFoundError(
            f"Input video not found: {input_file.resolve()}"
        )

    output_file.parent.mkdir(
        parents=True,
        exist_ok=True
    )

    model = YOLO("yolo11n.pt")

    capture = cv2.VideoCapture(str(input_file))

    if not capture.isOpened():
        raise RuntimeError(
            f"Cannot open video: {input_file.resolve()}"
        )

    fps = capture.get(cv2.CAP_PROP_FPS)
    width = int(
        capture.get(cv2.CAP_PROP_FRAME_WIDTH)
    )
    height = int(
        capture.get(cv2.CAP_PROP_FRAME_HEIGHT)
    )

    writer = cv2.VideoWriter(
        str(output_file),
        cv2.VideoWriter_fourcc(*"mp4v"),
        fps,
        (width, height)
    )

    try:
        while True:
            success, frame = capture.read()

            if not success:
                break

            results = model.track(
                frame,
                persist=True,
                tracker="bytetrack.yaml",
                classes=[0, 2, 3, 5, 7],
                conf=0.4,
                verbose=False
            )

            annotated_frame = results[0].plot()

            writer.write(annotated_frame)

    finally:
        capture.release()
        writer.release()

    print(
        f"Annotated video saved to: "
        f"{output_file.resolve()}"
    )


if __name__ == "__main__":
    main()
