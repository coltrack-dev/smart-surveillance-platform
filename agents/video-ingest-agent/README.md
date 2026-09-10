# video-ingest-agent

Rust media-node agent for the Surveillance Platform. It owns FFmpeg
processes; Java services remain responsible for authorization, business state,
camera metadata and orchestration.

Подробное объяснение Rust-конструкций и прохождения команды по коду приведено в
[`docs/RUST_CODE_GUIDE_RU.md`](docs/RUST_CODE_GUIDE_RU.md).

## Implemented MVP

- RTSP validation with `ffprobe`;
- idempotent start by `commandId`;
- one active pipeline per camera;
- RTSP publishing to MediaMTX or rolling HLS output;
- built-in HTTP delivery of generated HLS playlists and segments;
- codec copy, H.264 transcoding or automatic selection after ffprobe;
- `RUNNING` only after FFmpeg reports real output progress;
- continuous output progress watchdog after the pipeline reaches `RUNNING`;
- automatic reconnect when FFmpeg stays alive but output media time stops;
- last FFmpeg diagnostics in pipeline `lastError`;
- exponential reconnect (1–30 seconds);
- FFmpeg termination and reaping on stop/shutdown;
- removal of generated HLS files on stop;
- credentials redaction in agent-owned log fields;
- bearer-token protection;
- health endpoint and Prometheus metrics.

The agent keeps its actual pipeline state in memory. `IngestAgentStreamWorker`
retains the desired start request and recreates a missing pipeline with the same
`commandId` after an agent restart. Temporary connection and server errors keep
the Java worker in `RECONNECTING` with bounded retry backoff.

## Run

Prerequisites: Rust toolchain, FFmpeg and ffprobe.

```bash
export AGENT_API_TOKEN=change-me
export AGENT_BIND=127.0.0.1:8098
export AGENT_READY_TIMEOUT_SECONDS=30
export AGENT_OUTPUT_READY_TIMEOUT_SECONDS=15
export AGENT_OUTPUT_HEALTH_INTERVAL_SECONDS=15
export AGENT_OUTPUT_STALL_TIMEOUT_SECONDS=15
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

Pipeline status includes `lastProgressAtEpochMs` and `lastOutputTimeMs`.
`lastProgressAtEpochMs` confirms that the agent continues receiving FFmpeg
progress blocks, while `lastOutputTimeMs` must advance with output media time.
If it remains unchanged for `AGENT_OUTPUT_STALL_TIMEOUT_SECONDS`, the agent
terminates FFmpeg and follows the configured reconnect policy.

For RTSP output the agent keeps the pipeline in `STARTING` after the first
FFmpeg progress block until ffprobe can open the published MediaMTX URL and
find its video stream. If the output is still unavailable after
`AGENT_OUTPUT_READY_TIMEOUT_SECONDS`, FFmpeg is restarted according to the
same reconnect policy instead of exposing a false `RUNNING` state.

While an RTSP-output pipeline is `RUNNING`, the agent opens the published URL
again every `AGENT_OUTPUT_HEALTH_INTERVAL_SECONDS`. If MediaMTX no longer makes
the path readable for two consecutive checks even though FFmpeg still reports
progress, the agent stops that process and follows the normal reconnect policy.
The probe itself uses `AGENT_OUTPUT_READY_TIMEOUT_SECONDS` as its timeout.

## Integration boundary

`stream-service` calls this API and stores the desired/business state. The
agent returns technical state (`STARTING`, `RUNNING`, `RECONNECTING`, `FAILED`,
`STOPPED`). `IngestAgentStreamWorker` polls these states and publishes them
through the existing Kafka and WebSocket lifecycle adapters. Demo Compose
enables this mode with `STREAM_INGEST_AGENT_ENABLED=true`.

Do not expose port 8098 publicly. Keep it on the internal service network and
always configure `AGENT_API_TOKEN` outside local development.
