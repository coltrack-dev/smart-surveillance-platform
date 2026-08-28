import unittest
from dataclasses import asdict

from inference_worker.line_crossing import (
    LineCrossingDetector,
    LineDefinition,
    Point,
)


class LineCrossingDetectorTest(unittest.TestCase):
    def test_round_trips_worker_environment_shape(self) -> None:
        original = LineDefinition(
            id="diagonal",
            start=Point(0.1, 0.2),
            end=Point(0.9, 0.8),
            allowed_directions=("A_TO_B",),
            cooldown_seconds=0,
        )

        restored = LineDefinition.from_dict(asdict(original))

        self.assertEqual(original, restored)

    def test_detects_vertical_line_in_both_directions(self) -> None:
        line = LineDefinition(
            id="entrance",
            start=Point(0.5, 0.0),
            end=Point(0.5, 1.0),
            direction_labels={
                "A_TO_B": "RIGHT_TO_LEFT",
                "B_TO_A": "LEFT_TO_RIGHT",
            },
            hysteresis=0.01,
            minimum_track_age_frames=2,
        )
        detector = LineCrossingDetector([line])

        self.assertEqual([], detector.update(
            track_id=1, class_id=0, box=(20, 10, 40, 50),
            width=100, height=100, now=0.0,
        ))
        crossings = detector.update(
            track_id=1, class_id=0, box=(60, 10, 80, 50),
            width=100, height=100, now=1.0,
        )

        self.assertEqual(1, len(crossings))
        self.assertEqual("LEFT_TO_RIGHT", crossings[0].direction)
        self.assertEqual("entrance", crossings[0].line_id)

    def test_short_line_does_not_trigger_outside_segment(self) -> None:
        line = LineDefinition(
            id="door",
            start=Point(0.5, 0.4),
            end=Point(0.5, 0.6),
            hysteresis=0.0,
            minimum_track_age_frames=2,
        )
        detector = LineCrossingDetector([line])
        detector.update(
            track_id=1, class_id=0, box=(20, 0, 40, 10),
            width=100, height=100, now=0.0,
        )
        crossings = detector.update(
            track_id=1, class_id=0, box=(60, 0, 80, 10),
            width=100, height=100, now=1.0,
        )

        self.assertEqual([], crossings)

    def test_respects_class_and_direction_filters(self) -> None:
        line = LineDefinition(
            id="people-only",
            start=Point(0.5, 0.0),
            end=Point(0.5, 1.0),
            allowed_classes=(0,),
            allowed_directions=("A_TO_B",),
            hysteresis=0.0,
            minimum_track_age_frames=2,
        )
        detector = LineCrossingDetector([line])
        detector.update(
            track_id=2, class_id=2, box=(20, 20, 40, 40),
            width=100, height=100, now=0.0,
        )
        self.assertEqual([], detector.update(
            track_id=2, class_id=2, box=(60, 20, 80, 40),
            width=100, height=100, now=1.0,
        ))


if __name__ == "__main__":
    unittest.main()
