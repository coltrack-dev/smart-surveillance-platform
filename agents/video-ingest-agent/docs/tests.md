
```bash

docker run --rm \
  -v "$PWD/agents/video-ingest-agent:/app:ro" \
  -v surveillance-cargo-registry:/usr/local/cargo/registry \
  -v surveillance-agent-target:/target \
  -w /app \
  -e CARGO_TARGET_DIR=/target \
  rust:1.89-bookworm \
  cargo test --locked
  
  ```

---

```bash

docker run --rm \
  -v "$PWD/agents/video-ingest-agent:/app:ro" \
  -v surveillance-cargo-registry:/usr/local/cargo/registry \
  -v surveillance-agent-target:/target \
  -w /app \
  -e CARGO_TARGET_DIR=/target \
  rust:1.89-bookworm \
  cargo test --locked \
  reconnects_when_fake_ffmpeg_stops_advancing_output \
  -- --nocapture
  
```

## MediaMTX integration smoke test

The smoke test builds the runtime image, publishes a generated RTSP source,
starts an agent pipeline and verifies the MediaMTX master playlist, media
playlist, referenced segment and final `RUNNING` state.

```bash
agents/video-ingest-agent/tests/mediamtx-smoke.sh
```
