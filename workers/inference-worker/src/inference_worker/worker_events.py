from __future__ import annotations

import json
import logging
import os
import platform
import socket
import threading
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

import torch
from confluent_kafka import KafkaException, Producer


log = logging.getLogger(__name__)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class WorkerEventPublisher:
    def __init__(self) -> None:
        self.worker_id = os.getenv(
            "INFERENCE_WORKER_ID",
            f"{socket.gethostname()}-{uuid4().hex[:8]}",
        )
        self.status_topic = os.getenv(
            "ANALYTICS_JOB_STATUS_TOPIC",
            "analytics.job-status",
        )
        self.heartbeat_topic = os.getenv(
            "ANALYTICS_WORKER_HEARTBEAT_TOPIC",
            "analytics.worker-heartbeat",
        )
        self.delivery_errors: list[str] = []
        self._producer = Producer(
            {
                "bootstrap.servers": os.getenv(
                    "KAFKA_BOOTSTRAP_SERVERS",
                    "kafka:29092",
                ),
                "client.id": self.worker_id,
                "acks": "all",
                "enable.idempotence": True,
                "compression.type": "snappy",
            }
        )

    def publish_status(
        self,
        *,
        job_id: str,
        camera_id: str,
        status: str,
        job_type: str,
        recording_id: str | None = None,
        details: dict[str, Any] | None = None,
    ) -> None:
        event = {
            "eventId": str(uuid4()),
            "schemaVersion": 1,
            "eventType": "ANALYTICS_JOB_STATUS",
            "jobId": job_id,
            "jobType": job_type,
            "workerId": self.worker_id,
            "cameraId": camera_id,
            "recordingId": recording_id,
            "status": status,
            "occurredAt": utc_now(),
            "details": details or {},
        }
        self._publish(
            self.status_topic,
            job_id,
            event,
        )

    def publish_heartbeat(
        self,
        *,
        active_jobs: int,
        max_jobs: int,
    ) -> None:
        cuda_available = torch.cuda.is_available()
        event = {
            "eventId": str(uuid4()),
            "schemaVersion": 1,
            "eventType": "ANALYTICS_WORKER_HEARTBEAT",
            "workerId": self.worker_id,
            "status": "ONLINE",
            "activeJobs": active_jobs,
            "maxJobs": max_jobs,
            "host": socket.gethostname(),
            "platform": platform.platform(),
            "cudaAvailable": cuda_available,
            "cudaDeviceCount": torch.cuda.device_count(),
            "gpuName": (
                torch.cuda.get_device_name(0)
                if cuda_available
                else None
            ),
            "occurredAt": utc_now(),
        }
        self._publish(
            self.heartbeat_topic,
            self.worker_id,
            event,
        )

    def _publish(
        self,
        topic: str,
        key: str,
        event: dict[str, Any],
    ) -> None:
        payload = json.dumps(
            event,
            ensure_ascii=False,
        ).encode("utf-8")

        try:
            self._producer.produce(
                topic=topic,
                key=key.encode("utf-8"),
                value=payload,
                callback=self._delivery_callback,
            )
            self._producer.poll(0)
        except BufferError:
            self._producer.poll(1)
            self._producer.produce(
                topic=topic,
                key=key.encode("utf-8"),
                value=payload,
                callback=self._delivery_callback,
            )
        except KafkaException:
            log.exception("Cannot publish worker event topic=%s", topic)
            raise

    def _delivery_callback(self, error, message) -> None:
        if error is not None:
            self.delivery_errors.append(str(error))
            log.error("Worker event delivery failed: %s", error)
            return

        log.debug(
            "Worker event published topic=%s partition=%s offset=%s",
            message.topic(),
            message.partition(),
            message.offset(),
        )

    def close(self) -> None:
        remaining = self._producer.flush(10)
        if remaining or self.delivery_errors:
            raise KafkaException(
                "Worker events were not fully delivered: "
                f"remaining={remaining}, errors={self.delivery_errors}"
            )


class WorkerHeartbeat:
    def __init__(
        self,
        publisher: WorkerEventPublisher,
    ) -> None:
        self.publisher = publisher
        self.interval_seconds = float(
            os.getenv("INFERENCE_HEARTBEAT_SECONDS", "15")
        )
        if os.getenv("INFERENCE_EXECUTION_MODE", "process").lower() == "batched":
            self.max_jobs = int(os.getenv("INFERENCE_MAX_CAMERAS", "8"))
        else:
            self.max_jobs = int(
                os.getenv("INFERENCE_MAX_CONCURRENT_JOBS", "1")
            )
        self._active_jobs = 0
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = threading.Thread(
            target=self._run,
            name="worker-heartbeat",
            daemon=True,
        )

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=self.interval_seconds + 1)

    def set_active_jobs(self, value: int) -> None:
        with self._lock:
            self._active_jobs = value

    def _run(self) -> None:
        while not self._stop.is_set():
            with self._lock:
                active_jobs = self._active_jobs
            try:
                self.publisher.publish_heartbeat(
                    active_jobs=active_jobs,
                    max_jobs=self.max_jobs,
                )
            except Exception:
                log.exception("Cannot publish worker heartbeat")
            self._stop.wait(self.interval_seconds)
