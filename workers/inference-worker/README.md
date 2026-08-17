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

## Real-time job

The worker has two real-time execution modes:

- `process` (default and rollback mode) starts one `realtime_main` process and
  one YOLO model per camera;
- `batched` keeps one YOLO model on the GPU, reads every RTSP source in a
  separate capture thread, forms micro-batches and keeps an independent
  ByteTrack instance for every camera.

Both modes keep polling Kafka and can receive STOP while inference is running.
RTSP readers reconnect when a source is temporarily unavailable and limit
inference with `profile.targetFps`.

Start:

```json
{
  "eventType": "ANALYTICS_JOB",
  "schemaVersion": 1,
  "jobId": "bbed268f-e5ef-40f0-a61e-d34a659f24ca",
  "jobType": "REALTIME",
  "action": "START",
  "cameraId": "29b88ec8-2c36-4879-a7d8-f8e1a4ee4443",
  "source": {
    "type": "RTSP",
    "url": "rtsp://192.168.13.128:8554/people",
    "transport": "tcp"
  },
  "profile": {
    "model": "/models/yolo11n.pt",
    "classes": [0],
    "confidence": 0.5,
    "devicePreference": "cuda:0",
    "linePosition": 0.5,
    "targetFps": 10
  }
}
```

Stop (use the same `cameraId`; key Kafka messages by `cameraId`):

```json
{
  "eventType": "ANALYTICS_JOB",
  "schemaVersion": 1,
  "jobId": "d8430bbb-7d25-459c-8ab3-e6729ad53496",
  "jobType": "REALTIME",
  "action": "STOP",
  "cameraId": "29b88ec8-2c36-4879-a7d8-f8e1a4ee4443"
}
```

For multiple workers, `START` and `STOP` messages for a camera must have the
same Kafka key so they are assigned to the same partition. Production stream
ownership should additionally be stored outside a worker process so it can be
recovered after a consumer-group rebalance.

## Remote WSL node

Example environment values when the platform runs at `192.168.13.128`:

```bash
export KAFKA_BOOTSTRAP_SERVERS=192.168.13.128:9092
export KAFKA_INPUT_TOPICS=recording.events,analytics.jobs
export KAFKA_CONSUMER_GROUP=inference-workers
export RECORDING_SERVICE_URL=http://192.168.13.128:8095
export INFERENCE_WORKER_ID=wsl-rtx5080-01
export INFERENCE_WORK_DIRECTORY="$PWD/data/work"
export INFERENCE_EXECUTION_MODE=batched
export INFERENCE_MAX_CAMERAS=8
export INFERENCE_BATCH_SIZE=4
export INFERENCE_BATCH_MAX_WAIT_MS=20
export INFERENCE_SNAPSHOT_WORKERS=2
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

## Batched mode constraints

All simultaneously active cameras on one worker must use the same model,
device, classes and confidence threshold. `linePosition` and `targetFps` may be
different per camera. A conflicting START is rejected instead of silently
changing the profile of already running cameras.

The latest-frame buffer contains one frame per camera. If inference is slower
than a source, an old frame is replaced rather than queued. This intentionally
keeps real-time latency bounded. Snapshot uploads run outside the CUDA thread.

Start with `INFERENCE_BATCH_SIZE=4` and `INFERENCE_MAX_CAMERAS=8`, then tune
them using GPU memory, inference latency and dropped-frame metrics. To roll
back without changing code:

```bash
export INFERENCE_EXECUTION_MODE=process
export INFERENCE_MAX_CONCURRENT_JOBS=1
python -m inference_worker.recording_consumer
```
