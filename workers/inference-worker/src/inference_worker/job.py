from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


class UnsupportedAnalyticsJob(ValueError):
    """Raised when a Kafka message is not a supported analytics job."""


@dataclass(frozen=True)
class AnalyticsProfile:
    model: str | None = None
    classes: tuple[int, ...] = (0,)
    confidence: float | None = None
    device_preference: str | None = None
    line_position: float | None = None
    target_fps: float | None = None
    attributes: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(
        cls,
        value: dict[str, Any] | None,
    ) -> "AnalyticsProfile":
        value = value or {}
        classes = tuple(
            int(item)
            for item in value.get("classes", [0])
        )

        if not classes:
            raise ValueError("profile.classes must not be empty")

        return cls(
            model=value.get("model"),
            classes=classes,
            confidence=_optional_float(value.get("confidence")),
            device_preference=value.get("devicePreference"),
            line_position=_optional_float(value.get("linePosition")),
            target_fps=_optional_float(value.get("targetFps")),
            attributes=dict(value.get("attributes") or {}),
        )


@dataclass(frozen=True)
class AnalyticsSource:
    type: str
    url: str | None = None
    transport: str | None = None

    @classmethod
    def from_dict(
        cls,
        value: dict[str, Any] | None,
        *,
        default_type: str,
    ) -> "AnalyticsSource":
        value = value or {}
        return cls(
            type=str(value.get("type", default_type)).upper(),
            url=value.get("url"),
            transport=value.get("transport"),
        )


@dataclass(frozen=True)
class AnalyticsJob:
    job_id: str
    job_type: str
    action: str
    camera_id: str
    recording_id: str | None
    source: AnalyticsSource
    profile: AnalyticsProfile
    raw_message: dict[str, Any] = field(
        repr=False,
        compare=False,
        default_factory=dict,
    )

    @classmethod
    def from_message(
        cls,
        message: dict[str, Any],
    ) -> "AnalyticsJob":
        event_type = str(message.get("eventType", "")).upper()

        if event_type == "RECORDING_READY":
            recording_id = _required_string(message, "recordingId")
            return cls(
                job_id=str(
                    message.get("eventId")
                    or recording_id
                ),
                job_type="RECORDING",
                action="START",
                camera_id=_required_string(message, "cameraId"),
                recording_id=recording_id,
                source=AnalyticsSource(
                    type="RECORDING_SERVICE",
                ),
                profile=AnalyticsProfile(),
                raw_message=message,
            )

        job_type = str(message.get("jobType", "")).upper()
        if event_type != "ANALYTICS_JOB" and not job_type:
            raise UnsupportedAnalyticsJob(
                f"Unsupported analytics message: {message}"
            )

        if job_type not in {"RECORDING", "REALTIME"}:
            raise UnsupportedAnalyticsJob(
                f"Unsupported analytics jobType={job_type!r}"
            )

        action = str(message.get("action", "START")).upper()
        if action not in {"START", "STOP"}:
            raise UnsupportedAnalyticsJob(
                f"Unsupported analytics action={action!r}"
            )

        recording_id = message.get("recordingId")
        return cls(
            job_id=_required_string(message, "jobId"),
            job_type=job_type,
            action=action,
            camera_id=_required_string(message, "cameraId"),
            recording_id=(
                str(recording_id)
                if recording_id is not None
                else None
            ),
            source=AnalyticsSource.from_dict(
                message.get("source"),
                default_type=(
                    "RECORDING_SERVICE"
                    if job_type == "RECORDING"
                    else "RTSP"
                ),
            ),
            profile=AnalyticsProfile.from_dict(
                message.get("profile")
            ),
            raw_message=message,
        )


def _required_string(
    value: dict[str, Any],
    key: str,
) -> str:
    result = value.get(key)
    if result is None or not str(result).strip():
        raise ValueError(f"{key} is required")
    return str(result)


def _optional_float(value: Any) -> float | None:
    if value is None:
        return None
    return float(value)
