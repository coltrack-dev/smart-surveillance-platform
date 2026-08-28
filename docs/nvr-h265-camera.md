# H.265 NVR camera sources

The camera service stores RTSP passwords encrypted with AES-256-GCM. Generate
the application key once and keep it stable between restarts:

```bash
openssl rand -base64 32
```

Put the result in the local `.env` file:

```dotenv
CAMERA_CREDENTIALS_KEY=<generated Base64 value>
```

Do not commit `.env`. Losing or changing this key makes existing encrypted
camera passwords unreadable.

An XM/XMEye NVR channel can be registered with a URL template. The password is
accepted separately and is never returned by the public camera API:

```bash
curl -X POST http://localhost:8080/api/cameras \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "NVR - Channel 8",
    "rtspUrl": "rtsp://192.168.50.27:554/user={username}_password={password}_channel=8_stream=1.sdp?real_stream",
    "rtspUsername": "rt",
    "rtspPassword": "REPLACE_WITH_NEW_PASSWORD",
    "rtspUrlFormat": "XM",
    "videoProcessingMode": "AUTO",
    "autoStart": false
  }'
```

`AUTO` uses `ffprobe` to detect the source video codec. H.264 is copied into
HLS without re-encoding. HEVC/H.265 and other codecs are transcoded to H.264
for browser compatibility. Audio is currently omitted from live HLS.

Available processing modes:

| Mode | Behavior |
| --- | --- |
| `AUTO` | Probe codec; copy H.264 and transcode other codecs |
| `COPY` | Always copy the source video codec |
| `TRANSCODE_H264` | Always encode browser-compatible H.264 |

When updating a camera, omit `rtspPassword` or send it as an empty string to
preserve the existing encrypted password.

The resolved URL containing credentials is exposed only by the internal
camera-service endpoint used by stream-service and recording-service. The
gateway must not publish `/internal/**` routes.
