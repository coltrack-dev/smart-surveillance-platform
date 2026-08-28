from __future__ import annotations

from dataclasses import dataclass, field
import json
import os
from typing import Any, Iterable


@dataclass(frozen=True)
class Point:
    x: float
    y: float

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "Point":
        point = cls(float(value["x"]), float(value["y"]))
        if not 0.0 <= point.x <= 1.0 or not 0.0 <= point.y <= 1.0:
            raise ValueError("line coordinates must be between 0 and 1")
        return point


@dataclass(frozen=True)
class LineDefinition:
    id: str
    start: Point
    end: Point
    anchor: str = "BOTTOM_CENTER"
    allowed_directions: tuple[str, ...] = ()
    direction_labels: dict[str, str] = field(default_factory=dict)
    allowed_classes: tuple[int, ...] = ()
    cooldown_seconds: float = 2.0
    hysteresis: float = 0.02
    minimum_track_age_frames: int = 3

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "LineDefinition":
        line = cls(
            id=str(value.get("id") or "main-line"),
            start=Point.from_dict(value["start"]),
            end=Point.from_dict(value["end"]),
            anchor=str(value.get("anchor") or "BOTTOM_CENTER").upper(),
            allowed_directions=tuple(
                str(item).upper() for item in (
                    value.get("allowedDirections")
                    or value.get("allowed_directions")
                    or []
                )
            ),
            direction_labels={
                str(key).upper(): str(label)
                for key, label in (
                    value.get("directionLabels") or value.get("direction_labels") or {}
                ).items()
            },
            allowed_classes=tuple(int(item) for item in (
                value.get("allowedClasses") or value.get("allowed_classes") or []
            )),
            cooldown_seconds=float(_option(value, "cooldownSeconds", "cooldown_seconds", 2.0)),
            hysteresis=float(_option(value, "hysteresis", "hysteresis", 0.02)),
            minimum_track_age_frames=int(_option(
                value, "minimumTrackAgeFrames", "minimum_track_age_frames", 3
            )),
        )
        line.validate()
        return line

    def validate(self) -> None:
        if self.start == self.end:
            raise ValueError("line start and end must be different")
        if self.anchor not in {"BOTTOM_CENTER", "CENTER"}:
            raise ValueError("line anchor must be BOTTOM_CENTER or CENTER")
        if any(value not in {"A_TO_B", "B_TO_A"} for value in self.allowed_directions):
            raise ValueError("allowedDirections must contain A_TO_B or B_TO_A")
        if self.cooldown_seconds < 0:
            raise ValueError("cooldownSeconds must not be negative")
        if not 0 <= self.hysteresis < 0.5:
            raise ValueError("hysteresis must be between 0 and 0.5")
        if self.minimum_track_age_frames < 1:
            raise ValueError("minimumTrackAgeFrames must be positive")

    def pixels(self, width: int, height: int) -> tuple[tuple[int, int], tuple[int, int]]:
        return (
            (round(self.start.x * width), round(self.start.y * height)),
            (round(self.end.x * width), round(self.end.y * height)),
        )


@dataclass(frozen=True)
class Crossing:
    line_id: str
    direction: str
    direction_code: str


@dataclass
class _TrackState:
    previous_point: Point | None = None
    stable_side: int = 0
    age_frames: int = 0
    last_crossing_at: float = float("-inf")


class LineCrossingDetector:
    def __init__(self, lines: Iterable[LineDefinition]) -> None:
        self.lines = tuple(lines)
        if not self.lines:
            raise ValueError("at least one crossing line is required")
        self._states: dict[tuple[str, int], _TrackState] = {}

    def clear(self) -> None:
        self._states.clear()

    def update(
        self,
        *,
        track_id: int,
        class_id: int,
        box: tuple[int, int, int, int],
        width: int,
        height: int,
        now: float,
    ) -> list[Crossing]:
        results: list[Crossing] = []
        for line in self.lines:
            if line.allowed_classes and class_id not in line.allowed_classes:
                continue
            point = _anchor_point(box, width, height, line.anchor)
            key = (line.id, track_id)
            state = self._states.setdefault(key, _TrackState())
            state.age_frames += 1
            distance = _signed_distance(point, line)
            side = 0 if abs(distance) <= line.hysteresis else (1 if distance > 0 else -1)
            previous_point = state.previous_point
            previous_side = state.stable_side
            state.previous_point = point
            if side == 0:
                continue
            state.stable_side = side
            if previous_point is None or previous_side in {0, side}:
                continue
            if state.age_frames < line.minimum_track_age_frames:
                continue
            if now - state.last_crossing_at < line.cooldown_seconds:
                continue
            if not _segments_intersect(previous_point, point, line.start, line.end):
                continue
            direction_code = "A_TO_B" if previous_side < side else "B_TO_A"
            if line.allowed_directions and direction_code not in line.allowed_directions:
                continue
            state.last_crossing_at = now
            results.append(Crossing(
                line_id=line.id,
                direction=line.direction_labels.get(direction_code, direction_code),
                direction_code=direction_code,
            ))
        return results


def default_horizontal_line(position: float = 0.5) -> LineDefinition:
    return LineDefinition(
        id="main-line",
        start=Point(0.0, position),
        end=Point(1.0, position),
        direction_labels={"A_TO_B": "DOWN", "B_TO_A": "UP"},
    )


def lines_from_environment() -> tuple[LineDefinition, ...]:
    raw = os.getenv("ANALYTICS_LINES_JSON")
    if raw:
        return tuple(LineDefinition.from_dict(item) for item in json.loads(raw))
    return (default_horizontal_line(float(os.getenv("LINE_POSITION", "0.5"))),)


def draw_lines(frame: Any, lines: Iterable[LineDefinition]) -> None:
    import cv2

    height, width = frame.shape[:2]
    for line in lines:
        start, end = line.pixels(width, height)
        cv2.line(frame, start, end, (0, 0, 255), 2)
        cv2.putText(
            frame, line.id, start, cv2.FONT_HERSHEY_SIMPLEX,
            0.6, (0, 0, 255), 2, cv2.LINE_AA,
        )


def _anchor_point(
    box: tuple[int, int, int, int], width: int, height: int, anchor: str
) -> Point:
    x1, y1, x2, y2 = box
    x = (x1 + x2) / 2.0
    y = (y1 + y2) / 2.0 if anchor == "CENTER" else float(y2)
    return Point(x / width, y / height)


def _signed_distance(point: Point, line: LineDefinition) -> float:
    dx = line.end.x - line.start.x
    dy = line.end.y - line.start.y
    length = (dx * dx + dy * dy) ** 0.5
    return (dx * (point.y - line.start.y) - dy * (point.x - line.start.x)) / length


def _segments_intersect(a: Point, b: Point, c: Point, d: Point) -> bool:
    def orientation(p: Point, q: Point, r: Point) -> float:
        return (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)

    return orientation(a, b, c) * orientation(a, b, d) <= 0 and \
        orientation(c, d, a) * orientation(c, d, b) <= 0


def _option(value: dict[str, Any], camel: str, snake: str, default: Any) -> Any:
    selected = value.get(camel)
    if selected is None:
        selected = value.get(snake)
    return default if selected is None else selected
