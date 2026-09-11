#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.integration.yml"
CAMERA_ID=00000000-0000-0000-0000-000000000001

cleanup() {
  rm -f /tmp/video-ingest-agent-master.m3u8 /tmp/video-ingest-agent-media.m3u8
  docker compose -f "$COMPOSE_FILE" down --volumes --remove-orphans
}
trap cleanup EXIT INT TERM

docker compose -f "$COMPOSE_FILE" build agent
docker compose -f "$COMPOSE_FILE" up -d mediamtx agent source

i=0
until curl -fsS http://localhost:18098/ready >/dev/null; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    docker compose -f "$COMPOSE_FILE" logs
    exit 1
  fi
  sleep 1
done

curl -fsS -X POST \
  -H 'Authorization: Bearer integration-token' \
  -H 'Content-Type: application/json' \
  -d "{\"commandId\":\"00000000-0000-0000-0000-000000000002\",\"cameraId\":\"$CAMERA_ID\",\"rtspUrl\":\"rtsp://mediamtx:8554/source\",\"transport\":\"TCP\",\"videoMode\":\"COPY\",\"output\":{\"type\":\"RTSP\",\"url\":\"rtsp://mediamtx:8554/$CAMERA_ID\"},\"reconnect\":true}" \
  http://localhost:18098/v1/pipelines >/dev/null

i=0
until curl -fsS "http://localhost:18888/$CAMERA_ID/index.m3u8" > /tmp/video-ingest-agent-master.m3u8; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    docker compose -f "$COMPOSE_FILE" logs
    exit 1
  fi
  sleep 1
done

MEDIA_PLAYLIST=$(awk '!/^#/ && /\.m3u8$/ { print; exit }' /tmp/video-ingest-agent-master.m3u8)
test -n "$MEDIA_PLAYLIST"
curl -fsS "http://localhost:18888/$CAMERA_ID/$MEDIA_PLAYLIST" > /tmp/video-ingest-agent-media.m3u8
SEGMENT=$(awk '!/^#/ && /\.(ts|m4s)$/ { value=$0 } END { print value }' /tmp/video-ingest-agent-media.m3u8)
test -n "$SEGMENT"
curl -fsS -r 0-0 "http://localhost:18888/$CAMERA_ID/$SEGMENT" >/dev/null

curl -fsS \
  -H 'Authorization: Bearer integration-token' \
  "http://localhost:18098/v1/pipelines/$CAMERA_ID" |
grep -q '"state":"RUNNING"'

echo "video-ingest-agent MediaMTX smoke test passed"
