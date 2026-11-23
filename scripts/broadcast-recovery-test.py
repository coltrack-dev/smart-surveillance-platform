#!/usr/bin/env python3
"""Exercise isolated Broadcast desired-state recovery after component restarts."""

import json
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

GATEWAY = os.getenv("BROADCAST_TEST_GATEWAY", "http://127.0.0.1:18081").rstrip("/")
TOKEN = os.environ["BROADCAST_MANAGEMENT_TOKEN"]
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
AGENT = os.getenv("BROADCAST_IT_AGENT_CONTAINER", "broadcast-it-video-ingest-agent-1")


def request(path, method="GET", body=None, token=None):
    headers = {}
    payload = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body).encode()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        with OPENER.open(urllib.request.Request(GATEWAY + path, data=payload, headers=headers, method=method), timeout=5) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()
    except Exception:
        return 0, b""


def management(path, method="GET", body=None, expected=200):
    status, data = request(path, method, body, TOKEN)
    if status != expected:
        raise RuntimeError(f"{method} {path}: expected {expected}, got {status}")
    return json.loads(data) if data else None


def ffmpeg_pids():
    output = subprocess.check_output(["docker", "top", AGENT, "-eo", "pid,args"], text=True)
    return sorted(line.strip().split(None, 1)[0] for line in output.splitlines()[1:] if __import__("re").search(r"(?:^|\s)ffmpeg(?:\s|$)", line, __import__("re").I))


def wait_ready(public_id, timeout=50):
    deadline = time.monotonic() + timeout
    last_status = 0
    while time.monotonic() < deadline:
        status, data = request(f"/api/playback/{public_id}/token")
        if status == 200:
            try:
                auth = json.loads(data)
                url = urllib.parse.urljoin(GATEWAY + "/", auth["playlistUrl"])
                status, body = request(urllib.parse.urlparse(url).path + "?" + urllib.parse.urlparse(url).query)
                last_status = status
                if status == 200 and body.startswith(b"#EXTM3U"):
                    return
            except Exception:
                pass
        else:
            last_status = status
        time.sleep(1)
    raise RuntimeError(f"protected playlist did not recover (last HTTP status {last_status})")


def restart(container, label):
    subprocess.run(["docker", "restart", "--time", "5", container], check=True, capture_output=True, text=True, timeout=30)
    print(f"PASS: isolated {label} container restarted")


def verify(label, public_id):
    wait_ready(public_id)
    pids = ffmpeg_pids()
    if len(pids) != 1:
        raise RuntimeError(f"{label}: expected one FFmpeg PID, observed {len(pids)}")
    print(f"PASS: {label} recovered protected HLS with one FFmpeg process")


def main():
    camera = broadcast = None
    try:
        status, data = request("/api/v1/cameras", "POST", {
            "name": "Isolated broadcast recovery source",
            "rtspUrl": "rtsp://mediamtx:8554/test-source",
            "rtspUrlFormat": "STANDARD", "videoProcessingMode": "AUTO", "autoStart": False,
        })
        if status != 201:
            raise RuntimeError(f"camera create returned {status}")
        camera = json.loads(data)
        broadcast = management("/api/v1/broadcasts", "POST", {"cameraId": camera["id"]}, 201)
        management(f"/api/v1/broadcasts/{broadcast['id']}/enabled", "PUT", {"enabled": True})
        management(f"/api/v1/broadcasts/{broadcast['id']}/visibility", "PUT", {"visibility": "PUBLIC"})
        public_id = broadcast["publicId"]
        verify("initial desired-state reconciliation", public_id)

        restart("broadcast-it-stream-service-1", "stream-service")
        verify("stream-service restart", public_id)
        restart(AGENT, "ingest-agent")
        verify("ingest-agent restart", public_id)
        restart("broadcast-it-mediamtx-1", "MediaMTX")
        verify("MediaMTX restart", public_id)
        restart("broadcast-it-postgres-1", "PostgreSQL")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            ready = subprocess.run(["docker", "exec", "broadcast-it-postgres-1", "pg_isready", "-U", "broadcast_it", "-d", "broadcast_it"], capture_output=True, timeout=4)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("isolated PostgreSQL did not recover")
        verify("PostgreSQL restart", public_id)
        print("PASS: enabled Broadcast desired state survived stream-service, agent, MediaMTX and PostgreSQL restarts")
    finally:
        if broadcast:
            management(f"/api/v1/broadcasts/{broadcast['id']}", "DELETE", expected=204)
        if camera:
            request(f"/api/v1/cameras/{camera['id']}", "DELETE")


if __name__ == "__main__":
    main()
