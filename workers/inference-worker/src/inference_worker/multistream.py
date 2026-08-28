from __future__ import annotations

import logging
import os
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import cv2
import torch
from ultralytics import YOLO
from ultralytics.trackers.byte_tracker import BYTETracker
from ultralytics.utils import IterableSimpleNamespace, YAML
from ultralytics.utils.checks import check_yaml

from inference_worker.event_producer import AnalyticsEventProducer
from inference_worker.frame_buffer import FrameEnvelope, LatestFrameBuffer
from inference_worker.job import AnalyticsJob
from inference_worker.realtime_main import create_event
from inference_worker.snapshot_storage import SnapshotStorage
from inference_worker.line_crossing import (
    LineCrossingDetector,
    default_horizontal_line,
    draw_lines,
)


log = logging.getLogger(__name__)


@dataclass
class CameraRuntime:
    job: AnalyticsJob
    target_fps: float
    crossing_detector: LineCrossingDetector
    transport: str
    buffer: LatestFrameBuffer = field(default_factory=LatestFrameBuffer)
    stop_event: threading.Event = field(default_factory=threading.Event)
    capture_thread: threading.Thread | None = None
    tracker: BYTETracker | None = None
    started_at: float = field(default_factory=time.monotonic)
    source_frames: int = 0
    processed_frames: int = 0
    crossings: int = 0
    reconnects: int = 0


def _open_stream(url: str, transport: str) -> cv2.VideoCapture:
    os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = (
        f"rtsp_transport;{transport}|stimeout;15000000"
    )
    capture = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
    capture.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    return capture


class MultistreamManager:
    """Owns one YOLO model and independently tracks multiple RTSP cameras."""

    def __init__(self) -> None:
        self.batch_size = int(os.getenv("INFERENCE_BATCH_SIZE", "4"))
        self.batch_wait_seconds = (
            float(os.getenv("INFERENCE_BATCH_MAX_WAIT_MS", "20")) / 1000.0
        )
        self.reconnect_max_seconds = float(
            os.getenv("INFERENCE_RTSP_RECONNECT_MAX_SECONDS", "30")
        )
        self.cooldown_seconds = float(
            os.getenv("CROSSING_COOLDOWN_SECONDS", "2")
        )
        self.jpeg_quality = int(
            os.getenv("ANALYTICS_SNAPSHOT_JPEG_QUALITY", "75")
        )
        self._runtimes: dict[str, CameraRuntime] = {}
        self._registry_lock = threading.Lock()
        self._wakeup = threading.Event()
        self._shutdown = threading.Event()
        self._model: YOLO | None = None
        self._profile_signature: tuple[Any, ...] | None = None
        self._inference_thread: threading.Thread | None = None
        self._failure: BaseException | None = None
        self._round_robin_offset = 0
        self._snapshot_executor = ThreadPoolExecutor(
            max_workers=int(os.getenv("INFERENCE_SNAPSHOT_WORKERS", "2")),
            thread_name_prefix="snapshot",
        )
        self._producer = AnalyticsEventProducer(
            bootstrap_servers=os.getenv(
                "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"
            ),
            topic=os.getenv("KAFKA_TOPIC", "analytics.events"),
        )
        self._snapshot_storage = SnapshotStorage()
        self._snapshot_directory = Path(
            os.getenv(
                "ANALYTICS_SNAPSHOTS_DIRECTORY",
                "/tmp/analytics-snapshots",
            )
        )
        self._snapshot_directory.mkdir(parents=True, exist_ok=True)
        self._public_path = os.getenv(
            "ANALYTICS_SNAPSHOTS_PUBLIC_PATH",
            "/api/v1/analytics/snapshots",
        ).rstrip("/")

        if self.batch_size < 1:
            raise ValueError("INFERENCE_BATCH_SIZE must be positive")
        if self.batch_wait_seconds < 0:
            raise ValueError("INFERENCE_BATCH_MAX_WAIT_MS must not be negative")

    @property
    def active_jobs(self) -> int:
        with self._registry_lock:
            return len(self._runtimes)

    def contains(self, camera_id: str) -> bool:
        with self._registry_lock:
            return camera_id in self._runtimes

    def job_for(self, camera_id: str) -> AnalyticsJob | None:
        with self._registry_lock:
            runtime = self._runtimes.get(camera_id)
            return runtime.job if runtime else None

    def check_health(self) -> None:
        if self._failure is not None:
            raise RuntimeError("Multistream inference thread failed") from self._failure

    def start(self, job: AnalyticsJob) -> None:
        self.check_health()
        self._validate_job(job)
        signature = self._signature(job)

        with self._registry_lock:
            if job.camera_id in self._runtimes:
                raise ValueError(f"Camera is already running: {job.camera_id}")
            if self._profile_signature not in (None, signature):
                raise ValueError(
                    "All cameras in one batched worker must use the same "
                    "model, device, classes and confidence"
                )
            self._profile_signature = signature

            runtime = CameraRuntime(
                job=job,
                target_fps=job.profile.target_fps
                or float(os.getenv("ANALYTICS_TARGET_FPS", "10")),
                crossing_detector=LineCrossingDetector(
                    job.profile.lines or (
                        default_horizontal_line(
                            job.profile.line_position
                            or float(os.getenv("LINE_POSITION", "0.5"))
                        ),
                    )
                ),
                transport=(job.source.transport or "tcp").lower(),
            )
            runtime.tracker = self._new_tracker(runtime.target_fps)
            self._runtimes[job.camera_id] = runtime

        try:
            self._ensure_inference_thread(job)
            runtime.capture_thread = threading.Thread(
                target=self._capture_loop,
                args=(runtime,),
                name=f"capture-{job.camera_id[:8]}",
                daemon=True,
            )
            runtime.capture_thread.start()
        except Exception:
            with self._registry_lock:
                self._runtimes.pop(job.camera_id, None)
                if not self._runtimes:
                    self._profile_signature = None
            raise

        log.info(
            "Batched realtime camera started jobId=%s cameraId=%s url=%s",
            job.job_id,
            job.camera_id,
            job.source.url,
        )

    def stop(self, camera_id: str, timeout: float = 5.0) -> AnalyticsJob | None:
        with self._registry_lock:
            runtime = self._runtimes.pop(camera_id, None)
            if not self._runtimes:
                self._profile_signature = None
        if runtime is None:
            return None

        runtime.stop_event.set()
        self._wakeup.set()
        if runtime.capture_thread is not None:
            runtime.capture_thread.join(timeout=timeout)
            if runtime.capture_thread.is_alive():
                log.warning("Capture thread did not stop cameraId=%s", camera_id)
        log.info(
            "Batched realtime camera stopped jobId=%s cameraId=%s "
            "processedFrames=%s droppedFrames=%s crossings=%s",
            runtime.job.job_id,
            camera_id,
            runtime.processed_frames,
            runtime.buffer.dropped,
            runtime.crossings,
        )
        return runtime.job

    def close(self) -> None:
        self._shutdown.set()
        with self._registry_lock:
            camera_ids = list(self._runtimes)
        for camera_id in camera_ids:
            self.stop(camera_id)
        self._wakeup.set()
        if self._inference_thread is not None:
            self._inference_thread.join(timeout=10)
        self._snapshot_executor.shutdown(wait=True, cancel_futures=False)
        self._producer.close()

    def _ensure_inference_thread(self, job: AnalyticsJob) -> None:
        if self._model is None:
            device = self._device(job)
            if device.startswith("cuda") and not torch.cuda.is_available():
                raise RuntimeError(f"CUDA requested ({device}), but unavailable")
            model_file = job.profile.model or os.getenv("YOLO_MODEL", "yolo11n.pt")
            log.info("Loading shared YOLO model=%s device=%s", model_file, device)
            self._model = YOLO(model_file)
        if self._inference_thread is None:
            self._inference_thread = threading.Thread(
                target=self._inference_loop,
                name="batch-inference",
                daemon=True,
            )
            self._inference_thread.start()

    def _capture_loop(self, runtime: CameraRuntime) -> None:
        capture: cv2.VideoCapture | None = None
        backoff = 1.0
        next_frame_at = 0.0
        url = runtime.job.source.url or ""
        try:
            while not runtime.stop_event.is_set() and not self._shutdown.is_set():
                if capture is None or not capture.isOpened():
                    if capture is not None:
                        capture.release()
                    log.info("Connecting RTSP cameraId=%s url=%s", runtime.job.camera_id, url)
                    capture = _open_stream(url, runtime.transport)
                    if not capture.isOpened():
                        runtime.reconnects += 1
                        runtime.stop_event.wait(backoff)
                        backoff = min(backoff * 2, self.reconnect_max_seconds)
                        continue
                    runtime.crossing_detector.clear()
                    runtime.tracker = self._new_tracker(runtime.target_fps)
                    backoff = 1.0

                success, frame = capture.read()
                if not success:
                    runtime.reconnects += 1
                    capture.release()
                    capture = None
                    continue

                runtime.source_frames += 1
                now = time.monotonic()
                if now < next_frame_at:
                    continue
                next_frame_at = now + 1.0 / runtime.target_fps
                runtime.buffer.replace(
                    FrameEnvelope(runtime.source_frames, now, frame)
                )
                self._wakeup.set()
        finally:
            if capture is not None:
                capture.release()

    def _inference_loop(self) -> None:
        try:
            while not self._shutdown.is_set():
                batch = self._collect_batch()
                if not batch:
                    self._wakeup.wait(0.1)
                    self._wakeup.clear()
                    continue
                self._infer_batch(batch)
        except BaseException as error:
            self._failure = error
            log.exception("Multistream inference loop failed")

    def _collect_batch(self) -> list[tuple[CameraRuntime, FrameEnvelope]]:
        deadline = time.monotonic() + self.batch_wait_seconds
        selected: dict[str, tuple[CameraRuntime, FrameEnvelope]] = {}
        while len(selected) < self.batch_size:
            with self._registry_lock:
                runtimes = list(self._runtimes.values())
            if runtimes:
                offset = self._round_robin_offset % len(runtimes)
                runtimes = runtimes[offset:] + runtimes[:offset]
            for runtime in runtimes:
                if runtime.stop_event.is_set():
                    continue
                if runtime.job.camera_id in selected:
                    continue
                frame = runtime.buffer.take_fresh()
                if frame is not None:
                    selected[runtime.job.camera_id] = (runtime, frame)
                    if len(selected) >= self.batch_size:
                        break
            if selected and time.monotonic() >= deadline:
                break
            if not selected:
                break
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            self._wakeup.wait(min(remaining, 0.005))
            self._wakeup.clear()
        batch = list(selected.values())
        if batch:
            self._round_robin_offset += len(batch)
        return batch

    def _infer_batch(
        self, batch: list[tuple[CameraRuntime, FrameEnvelope]]
    ) -> None:
        assert self._model is not None
        batch = [item for item in batch if not item[0].stop_event.is_set()]
        if not batch:
            return
        first_job = batch[0][0].job
        results = self._model.predict(
            [envelope.frame for _, envelope in batch],
            classes=list(first_job.profile.classes),
            conf=first_job.profile.confidence
            or float(os.getenv("YOLO_CONFIDENCE", "0.5")),
            device=self._device(first_job),
            verbose=False,
        )
        for (runtime, envelope), result in zip(batch, results):
            if runtime.stop_event.is_set():
                continue
            runtime.processed_frames += 1
            boxes = result.boxes
            if boxes is None or len(boxes) == 0 or runtime.tracker is None:
                continue
            tracks = runtime.tracker.update(boxes.cpu().numpy(), envelope.frame)
            self._process_tracks(runtime, envelope, result, tracks)

    def _process_tracks(
        self,
        runtime: CameraRuntime,
        envelope: FrameEnvelope,
        result: Any,
        tracks: Any,
    ) -> None:
        height, width = envelope.frame.shape[:2]
        now = time.monotonic()
        for track in tracks:
            if len(track) < 6:
                continue
            x1, _y1, x2, y2 = (int(value) for value in track[:4])
            track_id = int(track[4])
            confidence = float(track[5])
            class_id = runtime.job.profile.classes[0]
            for crossing in runtime.crossing_detector.update(
                track_id=track_id,
                class_id=class_id,
                box=(x1, int(track[1]), x2, y2),
                width=width,
                height=height,
                now=now,
            ):
                event = create_event(
                    camera_id=runtime.job.camera_id,
                    job_id=runtime.job.job_id,
                    track_id=track_id,
                    direction=crossing.direction,
                    direction_code=crossing.direction_code,
                    line_id=crossing.line_id,
                    confidence=confidence,
                    frame_number=envelope.sequence,
                    stream_time_seconds=now - runtime.started_at,
                )
                annotated = result.plot()
                cv2.rectangle(annotated, (x1, int(track[1])), (x2, y2), (0, 255, 0), 2)
                draw_lines(annotated, runtime.crossing_detector.lines)
                runtime.crossings += 1
                self._snapshot_executor.submit(self._store_and_publish, event, annotated)
                log.info(
                    "Batched crossing eventId=%s cameraId=%s trackId=%s "
                    "lineId=%s direction=%s",
                    event["eventId"], runtime.job.camera_id, track_id,
                    crossing.line_id, event["attributes"]["direction"],
                )

    def _store_and_publish(self, event: dict[str, Any], frame: Any) -> None:
        with tempfile.NamedTemporaryFile(
            prefix=f"{event['eventId']}-",
            suffix=".jpg",
            dir=self._snapshot_directory,
            delete=False,
        ) as temporary:
            path = Path(temporary.name)
        try:
            if not cv2.imwrite(
                str(path), frame, [cv2.IMWRITE_JPEG_QUALITY, self.jpeg_quality]
            ):
                raise RuntimeError(f"Cannot save snapshot: {path}")
            key = self._snapshot_storage.upload(event["eventId"], path)
            event["attributes"]["snapshotKey"] = key
            event["attributes"]["snapshotUrl"] = (
                f"{self._public_path}/{event['eventId']}.jpg"
            )
            self._producer.publish(event)
        except Exception:
            log.exception("Cannot store/publish event eventId=%s", event["eventId"])
        finally:
            path.unlink(missing_ok=True)

    @staticmethod
    def _new_tracker(frame_rate: float) -> BYTETracker:
        config = IterableSimpleNamespace(
            **YAML.load(check_yaml("bytetrack.yaml"))
        )

        try:
            return BYTETracker(
                args=config,
                frame_rate=max(1, int(frame_rate))
            )
        except TypeError as error:
            if "unexpected keyword argument 'frame_rate'" not in str(error):
                raise

            log.warning(
                "BYTETracker does not support frame_rate; "
                "using installed Ultralytics API"
            )
            return BYTETracker(args=config)

    @staticmethod
    def _device(job: AnalyticsJob) -> str:
        configured = (job.profile.device_preference or os.getenv("YOLO_DEVICE", "auto")).lower()
        if configured == "auto":
            return "cuda:0" if torch.cuda.is_available() else "cpu"
        return configured

    @classmethod
    def _signature(cls, job: AnalyticsJob) -> tuple[Any, ...]:
        return (
            job.profile.model or os.getenv("YOLO_MODEL", "yolo11n.pt"),
            cls._device(job),
            job.profile.classes,
            job.profile.confidence or float(os.getenv("YOLO_CONFIDENCE", "0.5")),
        )

    @staticmethod
    def _validate_job(job: AnalyticsJob) -> None:
        if job.job_type != "REALTIME" or job.action != "START":
            raise ValueError("MultistreamManager.start requires REALTIME START")
        if job.source.type != "RTSP" or not job.source.url:
            raise ValueError("REALTIME source must contain an RTSP URL")
        target_fps = job.profile.target_fps or float(
            os.getenv("ANALYTICS_TARGET_FPS", "10")
        )
        line_position = job.profile.line_position or float(
            os.getenv("LINE_POSITION", "0.5")
        )
        if target_fps <= 0:
            raise ValueError("targetFps must be positive")
        if not 0 < line_position < 1:
            raise ValueError("linePosition must be between 0 and 1")
