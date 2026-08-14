import json
import logging

from confluent_kafka import (
    KafkaException,
    Producer,
)


log = logging.getLogger(__name__)


class AnalyticsEventProducer:

    def __init__(
        self,
        bootstrap_servers: str,
        topic: str,
    ) -> None:
        self.topic = topic

        self.delivery_errors: list[str] = []

        self.producer = Producer(
            {
                "bootstrap.servers": bootstrap_servers,
                "client.id": "inference-worker",
                "acks": "all",
                "enable.idempotence": True,
                "compression.type": "snappy",
            }
        )

    def _delivery_callback(
        self,
        error,
        message,
    ) -> None:
        if error is not None:
            self.delivery_errors.append(
                str(error)
            )

            log.error(
                "Kafka delivery failed: %s",
                error,
            )
            return

        log.info(
            "Event published "
            "topic=%s partition=%s offset=%s",
            message.topic(),
            message.partition(),
            message.offset(),
        )

    def publish(
        self,
        event: dict,
    ) -> None:
        payload = json.dumps(
            event,
            ensure_ascii=False,
        ).encode("utf-8")

        camera_id = str(
            event["cameraId"]
        ).encode("utf-8")

        try:
            self.producer.produce(
                topic=self.topic,
                key=camera_id,
                value=payload,
                callback=self._delivery_callback,
            )

            self.producer.poll(0)

        except BufferError:
            log.warning(
                "Kafka producer queue is full; waiting"
            )

            self.producer.poll(1)

            self.producer.produce(
                topic=self.topic,
                key=camera_id,
                value=payload,
                callback=self._delivery_callback,
            )

        except KafkaException:
            log.exception(
                "Cannot publish analytics event"
            )
            raise

    def close(self) -> None:
        remaining = self.producer.flush(10)

        if remaining > 0:
            log.error(
                "%s Kafka messages were not delivered",
                remaining,
            )

        if (
            remaining > 0
            or self.delivery_errors
        ):
            raise KafkaException(
                "Analytics events were not fully delivered: "
                f"remaining={remaining}, "
                f"errors={self.delivery_errors}"
            )
