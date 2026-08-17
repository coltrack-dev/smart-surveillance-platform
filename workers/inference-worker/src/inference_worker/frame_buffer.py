from __future__ import annotations

import threading
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class FrameEnvelope:
    sequence: int
    captured_at: float
    frame: Any


class LatestFrameBuffer:
    """A single-slot buffer which favours latency over frame completeness."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._frame: FrameEnvelope | None = None
        self._last_taken_sequence = 0
        self.dropped = 0

    def replace(self, value: FrameEnvelope) -> None:
        with self._lock:
            if (
                self._frame is not None
                and self._frame.sequence > self._last_taken_sequence
            ):
                self.dropped += 1
            self._frame = value

    def take_fresh(self) -> FrameEnvelope | None:
        with self._lock:
            if (
                self._frame is None
                or self._frame.sequence <= self._last_taken_sequence
            ):
                return None
            value = self._frame
            self._last_taken_sequence = value.sequence
            return value
