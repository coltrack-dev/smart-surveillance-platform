# Streaming preview: synthetic Demo People camera

This optional demonstration publishes the existing `data/analytics/input/people.mp4` as an authenticated RTSP source at the MediaMTX path `demo-people`. It uses the running `surveillance-streaming-preview` Compose network only. The MP4 is bind-mounted read-only, the publisher uses the existing `linuxserver/ffmpeg:7.1.1` image, and no host media ports are published.

The helper reads the publish/read credentials from `.env.streaming.local` and writes four narrowly scoped, mode-0600 files under the Git-ignored `.secrets/preview-demo-people/` directory. Compose bind-mounts only the credentials each container needs read-only at `/run/secrets`; no credentials are placed in container environment variables. The RTSP URL is assembled inside the container with percent-encoded user-info; it is not written into Compose command configuration or emitted in logs. FFmpeg copies H.264 packets (`-c:v copy -an`) and reads in real time with `-re -stream_loop -1` over RTSP/TCP.

## Commands

Run from the repository root. The optional override changes only the publish/read path ACLs for `demo-people` while retaining the UUID path rule for regular streams.

Prepare the local ignored secret mounts, then validate both Compose files without starting anything:

```bash
python3 scripts/prepare-preview-demo-secrets.py

docker compose --env-file .env.streaming.local \
  -p surveillance-streaming-preview \
  -f docker-compose.streaming.yml -f docker-compose.preview-demo.yml config -q
```

Apply the optional path ACL and start the publisher. The first command recreates only the preview MediaMTX container so it loads the isolated ACL override; its read-only config bind mount is reused and no MediaMTX data volume is changed. The second command starts only the optional publisher.

```bash
docker compose --env-file .env.streaming.local \
  -p surveillance-streaming-preview \
  -f docker-compose.streaming.yml -f docker-compose.preview-demo.yml \
  up -d --no-deps mediamtx

docker compose --env-file .env.streaming.local \
  -p surveillance-streaming-preview \
  -f docker-compose.streaming.yml -f docker-compose.preview-demo.yml \
  up -d --no-deps demo-people-publisher
```

Inspect the publisher/media service states without printing container environment:

```bash
docker compose --env-file .env.streaming.local \
  -p surveillance-streaming-preview \
  -f docker-compose.streaming.yml -f docker-compose.preview-demo.yml \
  ps mediamtx demo-people-publisher
```

Run a read-only authenticated `ffprobe` and a bounded 25-second RTSP read (the latter spans multiple source loops):

```bash
docker compose --env-file .env.streaming.local \
  -p surveillance-streaming-preview \
  -f docker-compose.streaming.yml -f docker-compose.preview-demo.yml \
  run --rm --no-deps demo-people-probe probe

docker compose --env-file .env.streaming.local \
  -p surveillance-streaming-preview \
  -f docker-compose.streaming.yml -f docker-compose.preview-demo.yml \
  run --rm --no-deps demo-people-probe duration 25
```

Stop only the publisher; this leaves MediaMTX and all preview data in place:

```bash
docker compose --env-file .env.streaming.local \
  -p surveillance-streaming-preview \
  -f docker-compose.streaming.yml -f docker-compose.preview-demo.yml \
  stop demo-people-publisher
```

Do not use `down -v`, prune, or the Surveillance Compose project for this demo.

## Camera and Broadcast fields

After signing in to `http://127.0.0.1:5174/app`, select the intended synthetic-owner organization and add:

| Field | Value |
|---|---|
| Camera name | `Demo People` |
| RTSP URL | `rtsp://mediamtx:8554/demo-people` |
| RTSP username | Value of `STREAMING_MEDIA_READ_USER` in `.env.streaming.local` (currently `stream-reader`) |
| RTSP password | Copy `STREAMING_MEDIA_READ_PASSWORD` from the ignored `.env.streaming.local` into the password field. Do not put credentials in the RTSP URL. |

The read account is required because Stream Service's ingest-agent reads this source from the preview Compose network. The publisher uses the separate publish account. Camera Service encrypts the password at rest and does not return it in Camera list/detail responses.

Then, in **Broadcasts**, create a Broadcast for `Demo People`, choose visibility, and enable it. The ingest-agent reads `demo-people` once and publishes the camera's normal UUID path to the existing MediaMTX service; Broadcast HLS continues through the authenticated Gateway proxy. Use the stable watch URL provided by the Dashboard; it must not contain a playback grant or RTSP credentials.

No tenant membership or permission is added by this demo. If the signed-in account is not an active member with a role that can manage Cameras/Broadcasts, perform these UI steps with the synthetic OWNER account.

## Observed verification (2026-11-25)

The isolated publisher was started with the `surveillance-streaming-preview` project and `demo-publisher` profile. The container remained `running` with exit code 0. The MP4 and all credential-file mounts were inspected as read-only. The existing `linuxserver/ffmpeg:7.1.1` image was reused; no image build or pull was performed.

The authenticated read probe returned H.264, 1920×1080, 30/1 fps. A separate authenticated 25-second RTSP read completed, spanning multiple 10.07-second source loops. A filtered MediaMTX log scan found zero entries matching the checked 401, 403, and authentication-failure markers. These observations verify the source is publishable and readable with the configured separate accounts; no credentials or authenticated URL were printed.

The following remain unverified: Camera registration, ingest-agent reuse/start, Broadcast lifecycle, HLS generation, browser playback, and permanent watch/iframe links. The preview database had no Camera or Broadcast rows before this attempt, and no authenticated synthetic OWNER browser session was available for authorized creation. Complete those steps manually in the Dashboard using the fields above; do not bypass tenant authorization. Public ingress remains disabled.
