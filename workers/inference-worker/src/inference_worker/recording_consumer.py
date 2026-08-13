import json
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import time

from pathlib import Path
from urllib.request import Request, urlopen

from confluent_kafka import (
    Consumer,
    KafkaError,
    KafkaException,
)


log = logging.getLogger(__name__)


def download_recording(
    recording_id: str,
    target: Path,
) -> None:

    base_url = os.getenv(
        "RECORDING_SERVICE_URL",
        "http://recording-service:8095",
    ).rstrip("/")

    url = (
        f"{base_url}"
        f"/api/recording-sources/{recording_id}"
    )

    log.info(
        "Downloading recording recordingId=%s url=%s",
        recording_id,
        url,
    )

    request = Request(
        url,
        headers={
            "Accept": "video/x-matroska",
        },
    )

    with (
        urlopen(
            request,
            timeout=600,
        ) as response,
        target.open("wb") as output,
    ):

        shutil.copyfileobj(
            response,
            output,
            length=1024 * 1024,
        )

    if target.stat().st_size == 0:

        raise RuntimeError(
            f"Downloaded recording is empty: "
            f"{recording_id}"
        )


def run_inference(
    event: dict,
) -> None:

    recording_id = str(
        event["recordingId"]
    )

    camera_id = str(
        event["cameraId"]
    )

    work_root = Path(
        os.getenv(
            "INFERENCE_WORK_DIRECTORY",
            "/data/work",
        )
    )

    work_root.mkdir(
        parents=True,
        exist_ok=True,
    )

    with tempfile.TemporaryDirectory(
        prefix=f"{recording_id}-",
        dir=work_root,
    ) as temporary_directory:

        job_directory = Path(
            temporary_directory
        )

        source = (
            job_directory
            / "source.mkv"
        )

        download_recording(
            recording_id,
            source,
        )

        environment = os.environ.copy()

        environment.update(
            {
                "WORKER_MODE": "single",
                "ANALYTICS_INPUT_FILE": str(source),
                "CAMERA_ID": camera_id,
                "RECORDING_ID": recording_id,
                "ANALYTICS_OUTPUT_VIDEO": str(
                    job_directory
                    / "annotated.mp4"
                ),
                "ANALYTICS_OUTPUT_EVENTS": str(
                    job_directory
                    / "events.jsonl"
                ),
            }
        )

        subprocess.run(
            [
                sys.executable,
                "-m",
                "inference_worker.main",
            ],
            env=environment,
            check=True,
        )


def main() -> None:

    logging.basicConfig(
        level=logging.INFO,
        format=(
            "%(asctime)s %(levelname)s "
            "%(name)s - %(message)s"
        ),
    )

    topic = os.getenv(
        "KAFKA_INPUT_TOPIC",
        "recording.events",
    )

    consumer = Consumer(
        {
            "bootstrap.servers": os.getenv(
                "KAFKA_BOOTSTRAP_SERVERS",
                "kafka:29092",
            ),
            "group.id": os.getenv(
                "KAFKA_CONSUMER_GROUP",
                "inference-workers",
            ),
            "auto.offset.reset": os.getenv(
                "KAFKA_AUTO_OFFSET_RESET",
                "earliest",
            ),
            "enable.auto.commit": False,
            "max.poll.interval.ms": int(
                os.getenv(
                    "KAFKA_MAX_POLL_INTERVAL_MS",
                    "86400000",
                )
            ),
        }
    )

    consumer.subscribe(
        [topic]
    )

    log.info(
        "Waiting for RecordingReadyEvent topic=%s",
        topic,
    )

    try:

        while True:

            message = consumer.poll(
                1.0
            )

            if message is None:
                continue

            if message.error():

                if (
                    message.error().code()
                    == KafkaError._PARTITION_EOF
                ):
                    continue

                raise KafkaException(
                    message.error()
                )

            event = json.loads(
                message.value()
                .decode("utf-8")
            )

            if (
                event.get("eventType")
                != "RECORDING_READY"
            ):

                log.warning(
                    "Skipping unsupported "
                    "recording event: %s",
                    event,
                )

                consumer.commit(
                    message=message,
                    asynchronous=False,
                )

                continue

            log.info(
                "Processing recording "
                "recordingId=%s cameraId=%s",
                event.get("recordingId"),
                event.get("cameraId"),
            )

            retry_delay = 10

            while True:

                try:

                    run_inference(
                        event
                    )

                    break

                except Exception:

                    log.exception(
                        "Inference failed "
                        "recordingId=%s; "
                        "retrying in %ss",
                        event.get("recordingId"),
                        retry_delay,
                    )

                    time.sleep(
                        retry_delay
                    )

                    retry_delay = min(
                        retry_delay * 2,
                        300,
                    )

            consumer.commit(
                message=message,
                asynchronous=False,
            )

            log.info(
                "Inference completed recordingId=%s",
                event["recordingId"],
            )

    finally:

        consumer.close()


if __name__ == "__main__":
    main()
