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

## Architecture diagrams

### 1. Live-stream components

```mermaid
flowchart TD
    UI["Vue UI"] --> GW["API Gateway"]
    GW --> STREAM["stream-service"]
    STREAM --> CAM["camera-service"]
    STREAM --> AGENT["video-ingest-agent"]
    AGENT --> PROBE["ffprobe"]
    AGENT --> FFMPEG["FFmpeg"]
    FFMPEG --> MTX["MediaMTX"]
    MTX --> GW
    GW -->|"/media-hls"| UI
    STREAM --> KAFKA["Kafka"]
    KAFKA --> WS["websocket-service"]
    WS --> UI
```

`stream-service` owns orchestration and desired state. The agent owns the
technical pipeline and FFmpeg process. MediaMTX accepts the normalized RTSP
stream and generates HLS, while Kafka and WebSocket carry statuses rather than
video data.

### 2. Live-stream startup sequence

```mermaid
sequenceDiagram
    actor User
    participant UI as UI and Gateway
    participant SS as Java control plane
    participant IA as ingest-agent
    participant MEDIA as ffprobe and FFmpeg
    participant MTX as MediaMTX

    User->>UI: Start stream
    UI->>SS: POST /api/v1/streams/{cameraId}/start
    SS->>SS: Load camera connection settings
    SS->>IA: POST /v1/pipelines
    Note over SS,IA: commandId, cameraId, RTSP, transport and output URL
    IA->>MEDIA: Probe input RTSP
    MEDIA-->>IA: Codec, resolution and FPS
    IA->>MEDIA: Start FFmpeg
    MEDIA->>MTX: Publish RTSP
    IA-->>SS: STARTING
    loop Poll pipeline state
        SS->>IA: GET /v1/pipelines/{cameraId}
        IA-->>SS: STARTING
    end
    IA->>MEDIA: Probe MediaMTX output
    MEDIA->>MTX: Open published path
    MTX-->>MEDIA: Video stream is readable
    MEDIA-->>IA: Output ready
    IA-->>SS: RUNNING
    SS-->>UI: StreamStarted through Kafka/WebSocket
    UI->>MTX: GET /media-hls/{cameraId}/index.m3u8 through Gateway
    MTX-->>UI: Playlist and segments
```

The agent does not report `RUNNING` merely because an FFmpeg process exists.
For RTSP output it first confirms that the published MediaMTX path contains a
readable video stream.

### 3. Agent internals

```mermaid
flowchart TD
    API["Axum HTTP API"] --> MANAGER["Pipeline Manager"]
    MANAGER --> REGISTRY["Pipeline Registry"]
    MANAGER --> SUPERVISOR["Pipeline Supervisor"]
    SUPERVISOR --> INPUT["Input probe"]
    SUPERVISOR --> PROCESS["FFmpeg process"]
    SUPERVISOR --> READY["Output readiness probe"]
    SUPERVISOR --> HEALTH["Output health probe"]
    SUPERVISOR --> WATCHDOG["Progress watchdog"]
    SUPERVISOR --> RECONNECT["Reconnect policy"]
    PROCESS --> PROGRESS["FFmpeg progress reader"]
    PROGRESS --> STATE["Pipeline state"]
    READY --> STATE
    HEALTH --> STATE
    WATCHDOG --> RECONNECT
    RECONNECT --> PROCESS
    STATE --> API
    STATE --> METRICS["Prometheus metrics"]
```

`PipelineManager` accepts API operations and maintains the camera registry.
Each active camera has a supervisor that owns at most one FFmpeg process.
Short-lived ffprobe processes are limited separately by
`AGENT_MAX_CONCURRENT_PROBES`.

### 4. Pipeline state machine

```mermaid
stateDiagram-v2
    [*] --> STARTING: START command
    STARTING --> RUNNING: progress and output ready
    STARTING --> RECONNECTING: probe or readiness failed
    STARTING --> FAILED: error without reconnect
    RUNNING --> RECONNECTING: FFmpeg exited
    RUNNING --> RECONNECTING: media time stalled
    RUNNING --> RECONNECTING: output health failed
    RUNNING --> STOPPED: STOP command
    RECONNECTING --> STARTING: backoff elapsed
    RECONNECTING --> STOPPED: STOP command
    RECONNECTING --> FAILED: reconnect disabled
    FAILED --> STARTING: new START command
    FAILED --> STOPPED: STOP command
    STOPPED --> STARTING: new START command
    STOPPED --> [*]
```

### 5. FFmpeg progress watchdog

```mermaid
sequenceDiagram
    participant FF as FFmpeg
    participant PR as Progress reader
    participant WD as Watchdog
    participant SUP as Supervisor

    loop Output advances
        FF-->>PR: progress block
        PR->>WD: Update lastProgressAt
        PR->>WD: Update lastOutputTime
    end
    Note over FF,SUP: Output media time stops advancing
    FF-->>PR: progress block
    PR->>WD: Update lastProgressAt only
    WD->>WD: Wait for stall timeout
    WD->>SUP: Output media time stalled
    SUP->>FF: Terminate process
    SUP->>SUP: Set RECONNECTING and wait backoff
    SUP->>FF: Start a new process
```

`lastProgressAtEpochMs` shows that FFmpeg still emits progress blocks.
`lastOutputTimeMs` shows that output media time is actually moving. If the
first value changes while the second remains unchanged, the process is treated
as stalled.

### 6. Periodic MediaMTX output health check

```mermaid
sequenceDiagram
    participant IA as ingest-agent
    participant FP as ffprobe
    participant MTX as MediaMTX
    participant FF as FFmpeg

    loop Every health interval
        IA->>FP: Probe output RTSP
        FP->>MTX: Open /{cameraId}
        MTX-->>FP: Video stream
        FP-->>IA: Success
    end
    IA->>FP: Probe output RTSP
    FP->>MTX: Open /{cameraId}
    MTX-->>FP: Stream unavailable
    FP-->>IA: Failure 1
    IA->>FP: Repeat probe
    FP->>MTX: Open /{cameraId}
    MTX-->>FP: Stream unavailable
    FP-->>IA: Failure 2
    IA->>FF: Terminate process
    IA->>IA: Set RECONNECTING
    IA->>FF: Restart after backoff
```

Two consecutive failures are required so that a single short MediaMTX or
network interruption does not immediately restart a healthy pipeline.

### 7. Stream stop sequence

```mermaid
sequenceDiagram
    actor User
    participant UI as Vue UI
    participant SS as stream-service
    participant IA as ingest-agent
    participant FF as FFmpeg

    User->>UI: Stop stream
    UI->>SS: POST /api/v1/streams/{cameraId}/stop
    SS->>IA: DELETE /v1/pipelines/{cameraId}
    IA->>FF: Terminate
    alt FFmpeg exits in time
        FF-->>IA: Exit
    else Termination timeout
        IA->>FF: Force termination
        FF-->>IA: Exit
    end
    IA->>IA: Release pipeline resources
    IA-->>SS: STOPPED
    SS-->>UI: StreamStopped through Kafka/WebSocket
```

The agent waits for and reaps its child process so that stopped pipelines do
not leave zombie FFmpeg processes.

### 8. Recovery after an agent restart

```mermaid
sequenceDiagram
    participant SS as stream-service
    participant IA as ingest-agent
    participant FF as FFmpeg

    Note over IA,FF: ingest-agent restarted
    Note over SS: Desired camera state is RUNNING
    SS->>IA: GET /v1/pipelines/{cameraId}
    IA-->>SS: 404 Pipeline not found
    SS->>SS: Detect state mismatch
    SS->>IA: POST /v1/pipelines
    Note over SS,IA: Same cameraId and commandId
    IA->>FF: Start FFmpeg
    IA-->>SS: STARTING
    IA-->>SS: RUNNING
```

Actual pipeline state is held in agent memory. The Java worker performs
reconciliation and recreates a missing pipeline from its retained desired
start request.

### 9. Relationship with recording-service

```mermaid
flowchart TD
    SOURCE["IP-camera / NVR"]
    SOURCE -->|"RTSP connection 1"| AGENT["video-ingest-agent"]
    AGENT --> LIVE_FF["FFmpeg for live"]
    LIVE_FF --> MTX["MediaMTX"]
    MTX --> HLS["HLS for browser"]
    SOURCE -->|"RTSP connection 2"| REC["recording-service"]
    REC --> REC_FF["Separate FFmpeg"]
    REC_FF --> MP4["MP4 archive"]
    MP4 --> S3["Local storage / S3"]
```

In the current implementation `recording-service` does not call the agent and
does not record the MediaMTX output. Simultaneous viewing and recording
therefore use two independent RTSP connections and two FFmpeg processes.

### 10. Control plane and media plane

```mermaid
flowchart TD
    subgraph CONTROL["Control plane"]
        UI["Vue UI"]
        GW["API Gateway"]
        SS["stream-service"]
        CS["camera-service"]
        K["Kafka"]
    end
    subgraph MEDIA["Media plane"]
        SOURCE["Camera / NVR"]
        IA["video-ingest-agent"]
        FF["FFmpeg"]
        MTX["MediaMTX"]
        HLS["HLS client"]
    end
    UI --> GW
    GW --> SS
    SS --> CS
    SS --> IA
    SS --> K
    SOURCE --> IA
    IA --> FF
    FF --> MTX
    MTX --> HLS
```

The control plane decides which stream should run. The media plane reads,
normalizes and transports the video itself.

## Run

Prerequisites: Rust toolchain, FFmpeg and ffprobe.

```bash
export AGENT_API_TOKEN=change-me
export AGENT_BIND=127.0.0.1:8098
export AGENT_READY_TIMEOUT_SECONDS=30
export AGENT_OUTPUT_READY_TIMEOUT_SECONDS=15
export AGENT_OUTPUT_HEALTH_INTERVAL_SECONDS=15
export AGENT_OUTPUT_STALL_TIMEOUT_SECONDS=15
export AGENT_MAX_PIPELINES=8
export AGENT_MAX_CONCURRENT_PROBES=4
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
curl http://127.0.0.1:8098/ready
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

`AGENT_MAX_PIPELINES` rejects additional cameras with HTTP 429 before another
FFmpeg process is created. `AGENT_MAX_CONCURRENT_PROBES` bounds startup,
readiness and periodic ffprobe processes across all cameras.

## Integration boundary

`stream-service` calls this API and stores the desired/business state. The
agent returns technical state (`STARTING`, `RUNNING`, `RECONNECTING`, `FAILED`,
`STOPPED`). `IngestAgentStreamWorker` polls these states and publishes them
through the existing Kafka and WebSocket lifecycle adapters. Demo Compose
enables this mode with `STREAM_INGEST_AGENT_ENABLED=true`.

Do not expose port 8098 publicly. Keep it on the internal service network and
always configure `AGENT_API_TOKEN` outside local development.
