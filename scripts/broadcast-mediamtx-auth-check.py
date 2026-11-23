#!/usr/bin/env python3
"""Verify MediaMTX roles against the isolated broadcast-it stack only."""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

GATEWAY = os.getenv("BROADCAST_TEST_GATEWAY", "http://127.0.0.1:18081").rstrip("/")
MANAGEMENT = os.environ["BROADCAST_MANAGEMENT_TOKEN"]
PUBLISH_USER = os.environ["STREAMING_MEDIA_PUBLISH_USER"]
PUBLISH_PASSWORD = os.environ["STREAMING_MEDIA_PUBLISH_PASSWORD"]
READ_USER = os.environ["STREAMING_MEDIA_READ_USER"]
READ_PASSWORD = os.environ["STREAMING_MEDIA_READ_PASSWORD"]
AGENT_CONTAINER = os.getenv("BROADCAST_IT_AGENT_CONTAINER", "broadcast-it-video-ingest-agent-1")
STREAM_CONTAINER = os.getenv("BROADCAST_IT_STREAM_CONTAINER", "broadcast-it-stream-service-1")
MEDIA_CONTAINER = os.getenv("BROADCAST_IT_MEDIAMTX_CONTAINER", "broadcast-it-mediamtx-1")
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(path, method="GET", body=None, token=None):
    headers = {}
    payload = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body).encode()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(GATEWAY + path, data=payload, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def management(path, method="GET", body=None, expected=200):
    status, data = request(path, method, body, MANAGEMENT)
    if status != expected:
        raise RuntimeError(f"{method} {path}: expected {expected}, got {status}")
    return json.loads(data) if data else None


def in_container(container, args, extra_env=None, timeout=10):
    command = ["docker", "exec"]
    for name, value in (extra_env or {}).items():
        command.extend(["-e", f"{name}={value}"])
    command.extend([container, *args])
    return subprocess.run(command, capture_output=True, text=True, timeout=timeout)


def expect(label, result, wanted):
    if result != wanted:
        raise RuntimeError(f"{label}: expected {wanted}, got {result}")
    print(f"PASS: {label} ({wanted})")


def main():
    camera = broadcast = None
    try:
        status, data = request("/api/v1/cameras", "POST", {
            "name": "Isolated MediaMTX auth verification source",
            "rtspUrl": "rtsp://mediamtx:8554/test-source",
            "rtspUrlFormat": "STANDARD", "videoProcessingMode": "AUTO", "autoStart": False,
        })
        expect("camera creation", status, 201)
        camera = json.loads(data)
        broadcast = management("/api/v1/broadcasts", "POST", {"cameraId": camera["id"]}, 201)
        management(f"/api/v1/broadcasts/{broadcast['id']}/enabled", "PUT", {"enabled": True})
        management(f"/api/v1/broadcasts/{broadcast['id']}/visibility", "PUT", {"visibility": "PUBLIC"})

        deadline = time.monotonic() + 45
        http_status = 0
        while time.monotonic() < deadline:
            basic_env = {"CHECK_USER": READ_USER, "CHECK_PASSWORD": READ_PASSWORD}
            probe = in_container(STREAM_CONTAINER, ["sh", "-c", "curl --silent --output /dev/null --write-out '%{http_code}' --user \"$CHECK_USER:$CHECK_PASSWORD\" http://mediamtx:8888/$CHECK_PATH/index.m3u8"], {**basic_env, "CHECK_PATH": camera["id"]})
            try:
                http_status = int(probe.stdout.strip())
            except ValueError:
                http_status = 0
            if http_status == 200:
                break
            time.sleep(1)
        expect("read role has HTTP HLS access to UUID output", http_status, 200)

        url = f"rtsp://mediamtx:8554/{camera['id']}"
        probe_base = ["ffprobe", "-v", "error", "-rtsp_transport", "tcp", "-show_entries", "stream=codec_name", "-of", "csv=p=0"]
        def rtsp_url(user, password, path):
            credentials = urllib.parse.quote(user, safe="") + ":" + urllib.parse.quote(password, safe="") + "@"
            return f"rtsp://{credentials}mediamtx:8554/{path}"

        success = in_container(AGENT_CONTAINER, [*probe_base, rtsp_url(READ_USER, READ_PASSWORD, camera["id"])], timeout=12)
        if success.returncode != 0:
            diagnostic = success.stderr.replace(READ_PASSWORD, "[redacted]").replace(PUBLISH_PASSWORD, "[redacted]")[:500]
            raise RuntimeError(f"read role RTSP probe failed (exit {success.returncode}): {diagnostic}")
        expect("read role has RTSP access to UUID output", success.returncode, 0)

        no_auth = in_container(AGENT_CONTAINER, [*probe_base, url], timeout=8)
        expect("unauthenticated RTSP read is rejected", no_auth.returncode == 0, False)
        wrong_http = in_container(STREAM_CONTAINER, ["sh", "-c", "curl --silent --output /dev/null --write-out '%{http_code}' --user \"$CHECK_USER:wrong-password\" http://mediamtx:8888/$CHECK_PATH/index.m3u8"], {"CHECK_USER": READ_USER, "CHECK_PATH": camera["id"]})
        expect("wrong HTTP read password returns 401", wrong_http.stdout.strip(), "401")
        no_http_auth = in_container(STREAM_CONTAINER, ["sh", "-c", "curl --silent --output /dev/null --write-out '%{http_code}' http://mediamtx:8888/$CHECK_PATH/index.m3u8"], {"CHECK_PATH": camera["id"]})
        expect("unauthenticated HTTP HLS read returns 401", no_http_auth.stdout.strip(), "401")

        publish_path = str(uuid.uuid4())
        ffmpeg = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi", "-i", "testsrc=size=64x64:rate=1", "-t", "3", "-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p", "-f", "rtsp", "-rtsp_transport", "tcp"]
        publisher = in_container(STREAM_CONTAINER, [*ffmpeg, rtsp_url(PUBLISH_USER, PUBLISH_PASSWORD, publish_path)], timeout=10)
        expect("publish role can publish to UUID path", publisher.returncode, 0)
        read_only_publish = in_container(STREAM_CONTAINER, [*ffmpeg, rtsp_url(READ_USER, READ_PASSWORD, str(uuid.uuid4()))], timeout=8)
        expect("read-only role cannot publish", read_only_publish.returncode != 0, True)

        values = [PUBLISH_USER, PUBLISH_PASSWORD, READ_USER, READ_PASSWORD]
        checked = [MEDIA_CONTAINER, AGENT_CONTAINER, STREAM_CONTAINER, "broadcast-it-rtsp-test-publisher-1"]
        found = False
        for container in checked:
            logs = subprocess.run(["docker", "logs", "--tail", "500", container], capture_output=True, text=True).stdout
            found = found or any(secret and secret in logs for secret in values)
        expect("MediaMTX credentials absent from checked container logs", found, False)
        print("PASS: effective test-only authInternalUsers environment roles verified; isolated anonymous test-source read is not a production grant")
    finally:
        if broadcast:
            management(f"/api/v1/broadcasts/{broadcast['id']}", "DELETE", expected=204)
        if camera:
            request(f"/api/v1/cameras/{camera['id']}", "DELETE")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Do not print subprocess stdout/stderr; FFmpeg/RTSP errors can include credential URLs.
        sys.exit(str(error))
