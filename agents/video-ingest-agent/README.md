# video-ingest-agent

Rust media-node agent for the Smart Surveillance Platform. It owns FFmpeg
processes; Java services remain responsible for authorization, business state,
camera metadata and orchestration.

## Implemented MVP

- RTSP validation with `ffprobe`;
- idempotent start by `commandId`;
- one active pipeline per camera;
- RTSP publishing to MediaMTX or rolling HLS output;
- built-in HTTP delivery of generated HLS playlists and segments;
- codec copy or H.264 transcoding;
- exponential reconnect (1–30 seconds);
- FFmpeg termination and reaping on stop/shutdown;
- removal of generated HLS files on stop;
- credentials redaction in agent-owned log fields;
- bearer-token protection;
- health endpoint and Prometheus metrics.

This MVP keeps desired state in memory. The controlling `stream-service` should
reconcile its desired state with `GET /v1/pipelines` after either side restarts.

## Run

Prerequisites: Rust toolchain, FFmpeg and ffprobe.

```bash
export AGENT_API_TOKEN=change-me
export AGENT_BIND=127.0.0.1:8098
cargo run
```

The Docker image contains FFmpeg:

```bash
docker build -t surveillance/video-ingest-agent:demo .
docker run --rm \
  --network surveillance-demo_default \
  -p 127.0.0.1:8098:8098 \
  -e AGENT_BIND=0.0.0.0:8098 \
  -e AGENT_API_TOKEN=change-me \
  -v "$PWD/data:/data" \
  surveillance/video-ingest-agent:demo
```

## API

Health does not require a token:

```bash
curl http://127.0.0.1:8098/health
```

Publish an NVR channel to MediaMTX without transcoding:

```bash
curl -X POST http://127.0.0.1:8098/v1/pipelines \
  -H 'Authorization: Bearer change-me' \
  -H 'Content-Type: application/json' \
  -d '{
    "commandId": "781d7e31-c597-4a97-9608-f32f721cea62",
    "cameraId": "29b88ec8-2c36-4879-a7d8-f8e1a4ee4443",
    "rtspUrl": "rtsp://user:password@nvr/stream",
    "transport": "TCP",
    "videoMode": "COPY",
    "output": {
      "type": "RTSP",
      "url": "rtsp://mediamtx:8554/camera-29b88ec8"
    },
    "reconnect": true
  }'
```

Create browser-compatible HLS. `H264` is required when the NVR supplies H.265
and playback is expected in an ordinary browser:

```bash
curl -X POST http://127.0.0.1:8098/v1/pipelines \
  -H 'Authorization: Bearer change-me' \
  -H 'Content-Type: application/json' \
  -d '{
    "cameraId": "29b88ec8-2c36-4879-a7d8-f8e1a4ee4443",
    "rtspUrl": "rtsp://user:password@nvr/stream",
    "videoMode": "H264",
    "output": {
      "type": "HLS",
      "segmentSeconds": 2,
      "playlistSegments": 6
    }
  }'
```

List, inspect and stop pipelines:

```bash
curl -H 'Authorization: Bearer change-me' \
  http://127.0.0.1:8098/v1/pipelines

curl -H 'Authorization: Bearer change-me' \
  http://127.0.0.1:8098/v1/pipelines/29b88ec8-2c36-4879-a7d8-f8e1a4ee4443

curl -X DELETE -H 'Authorization: Bearer change-me' \
  http://127.0.0.1:8098/v1/pipelines/29b88ec8-2c36-4879-a7d8-f8e1a4ee4443
```

Prometheus metrics:

```bash
curl http://127.0.0.1:8098/metrics
```

## Integration boundary

`stream-service` calls this API and stores the desired/business state. The
agent returns technical state (`STARTING`, `RUNNING`, `RECONNECTING`, `FAILED`,
`STOPPED`). A later Kafka event adapter can publish status changes without
moving business logic into this process.

Do not expose port 8098 publicly. Keep it on the internal service network and
always configure `AGENT_API_TOKEN` outside local development.
