# Inference worker

The inference worker is an independently deployable GPU node. It does not
need to share a Docker network or filesystem with the platform. It only needs:

- network access to Kafka;
- HTTP access to `recording-service`, or an absolute URL in `source.url`;
- access to the snapshot object storage;
- a local YOLO model and working directory.

## Kafka topics

| Topic | Direction | Purpose |
|---|---|---|
| `recording.events` | platform -> worker | Legacy `RECORDING_READY` events |
| `analytics.jobs` | platform -> worker | Explicit recording or real-time jobs |
| `analytics.events` | worker -> platform | Detected objects and crossings |
| `analytics.job-status` | worker -> platform | `RUNNING`, `RETRYING`, `COMPLETED`, or `REJECTED` |
| `analytics.worker-heartbeat` | worker -> platform | Node health, load, and GPU information |

The worker accepts a comma-separated list through `KAFKA_INPUT_TOPICS`.
`KAFKA_INPUT_TOPIC` remains supported for old deployments.

## Recording job

```json
{
  "eventType": "ANALYTICS_JOB",
  "schemaVersion": 1,
  "jobId": "d25daebd-3064-44d4-bdf3-2ac3493ab97d",
  "jobType": "RECORDING",
  "cameraId": "29b88ec8-2c36-4879-a7d8-f8e1a4ee4443",
  "recordingId": "f9a5af6a-eb1b-4ffe-83ea-7ddba2b95480",
  "source": {
    "type": "RECORDING_SERVICE",
    "url": "http://192.168.13.128:8095/api/recording-sources/f9a5af6a-eb1b-4ffe-83ea-7ddba2b95480"
  },
  "profile": {
    "model": "/models/yolo11n.pt",
    "classes": [0],
    "confidence": 0.5,
    "devicePreference": "cuda:0",
    "linePosition": 0.5
  }
}
```

The URL must be reachable from the GPU node. If it is omitted, the worker
builds it from `RECORDING_SERVICE_URL` and `recordingId`.

`REALTIME` is reserved by the contract but is currently returned as
`REJECTED` with `errorCode=UNSUPPORTED_JOB_TYPE`. The streaming runner should
be introduced separately so it can have cancellation, reconnect, and stream
ownership semantics.

## Remote WSL node

Example environment values when the platform runs at `192.168.13.128`:

```bash
export KAFKA_BOOTSTRAP_SERVERS=192.168.13.128:9092
export KAFKA_INPUT_TOPICS=recording.events,analytics.jobs
export KAFKA_CONSUMER_GROUP=inference-workers
export RECORDING_SERVICE_URL=http://192.168.13.128:8095
export INFERENCE_WORKER_ID=wsl-rtx5080-01
export INFERENCE_WORK_DIRECTORY="$PWD/data/work"
export YOLO_MODEL=/models/yolo11n.pt
export YOLO_DEVICE=cuda:0
export KAFKA_ENABLED=true
```

Run the long-lived consumer, not `inference_worker.main` directly:

```bash
python -m inference_worker.recording_consumer
```

All GPU nodes must use the same consumer group when each job should be claimed
by exactly one node. Give every node a unique `INFERENCE_WORKER_ID`.
