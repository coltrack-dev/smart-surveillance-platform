import logging
import os
from pathlib import Path

import boto3


log = logging.getLogger(__name__)


class SnapshotStorage:

    def __init__(self) -> None:
        self.bucket = os.environ["WASABI_BUCKET"]

        self.prefix = os.getenv(
            "ANALYTICS_SNAPSHOTS_S3_PREFIX",
            "analytics/snapshots",
        ).strip("/")

        self.client = boto3.client(
            "s3",
            endpoint_url=os.environ["WASABI_ENDPOINT"],
            region_name=os.getenv(
                "WASABI_REGION",
                "us-central-1",
            ),
            aws_access_key_id=os.environ["WASABI_ACCESS_KEY"],
            aws_secret_access_key=os.environ["WASABI_SECRET_KEY"],
        )

    def upload(
        self,
        event_id: str,
        snapshot: Path,
    ) -> str:
        key = f"{self.prefix}/{event_id}.jpg"

        self.client.upload_file(
            str(snapshot),
            self.bucket,
            key,
            ExtraArgs={
                "ContentType": "image/jpeg",
            },
        )

        log.info(
            "Snapshot uploaded eventId=%s s3://%s/%s",
            event_id,
            self.bucket,
            key,
        )

        return key
