#!/usr/bin/env python3
"""Exercise the Broadcast API against an already-started isolated test stack."""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

GATEWAY = os.environ.get("BROADCAST_TEST_GATEWAY", "http://127.0.0.1:18081").rstrip("/")
PLAYER = os.environ.get("BROADCAST_TEST_PLAYER", "http://127.0.0.1:15173").rstrip("/")
MANAGEMENT = os.environ.get("BROADCAST_MANAGEMENT_TOKEN")
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))
if not MANAGEMENT:
    sys.exit("Set BROADCAST_MANAGEMENT_TOKEN to the test stack's management token")


def request(url, method="GET", body=None, token=None, expected=None):
    headers = {}
    payload = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body).encode()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        with NO_PROXY.open(req, timeout=10) as response:
            status, data = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read()
    if expected is not None and status != expected:
        raise RuntimeError(f"{method} {urlparse_path(url)}: expected {expected}, received {status}")
    return status, data


def urlparse_path(url):
    return urllib.parse.urlparse(url).path


def json_request(url, method="GET", body=None, token=MANAGEMENT, expected=200):
    status, data = request(url, method, body, token, expected)
    return json.loads(data) if data else None


def main():
    camera = broadcast = None
    base = f"{GATEWAY}/api/v1"
    try:
        camera = json_request(f"{base}/cameras", "POST", {
            "name": "Isolated broadcast integration source",
            "rtspUrl": "rtsp://mediamtx:8554/test-source",
            "rtspUrlFormat": "STANDARD",
            "videoProcessingMode": "AUTO",
            "autoStart": False,
        }, token=None, expected=201)
        camera_id = camera["id"]

        request(f"{base}/broadcasts", "GET", token=None, expected=401)
        broadcast = json_request(f"{base}/broadcasts", "POST", {"cameraId": camera_id}, expected=201)
        duplicate = json_request(f"{base}/broadcasts", "POST", {"cameraId": camera_id}, expected=200)
        assert duplicate["id"] == broadcast["id"], "create must be idempotent per camera"
        broadcast_id, public_id = broadcast["id"], broadcast["publicId"]

        request(f"{GATEWAY}/api/playback/{public_id}/token", expected=401)
        json_request(f"{base}/broadcasts/{broadcast_id}/enabled", "PUT", {"enabled": True})
        json_request(f"{base}/broadcasts/{broadcast_id}/visibility", "PUT", {"visibility": "PUBLIC"})

        info = json_request(f"{base}/broadcasts/{broadcast_id}/playback")
        assert info["playerUrl"] == f"{PLAYER}/watch/{public_id}", "watch URL must be permanent"
        assert f"{PLAYER}/watch/{public_id}" in info["embedHtml"], "iframe URL must be stable"
        assert "referrerpolicy=\"no-referrer\"" in info["embedHtml"]
        status, _ = request(info["playerUrl"], expected=200)
        status, _ = request(f"{PLAYER}/watch/{public_id}?embed=1", expected=200)

        authorization = json_request(f"{GATEWAY}/api/playback/{public_id}/token")
        playlist_url = urllib.parse.urljoin(GATEWAY + "/", authorization["playlistUrl"])
        token_parts = urllib.parse.urlparse(playlist_url)
        query = urllib.parse.parse_qs(token_parts.query)
        issued_token = query["token"][0]
        tampered_token = issued_token[:-1] + ("A" if issued_token[-1] != "A" else "B")
        tampered_url = urllib.parse.urlunparse(token_parts._replace(
            query=urllib.parse.urlencode({"token": tampered_token})
        ))
        request(tampered_url, expected=401)
        traversal_url = f"{GATEWAY}/broadcast-play/{public_id}/%2e%2e%2Fsecret?token={urllib.parse.quote(issued_token)}"
        traversal_status, _ = request(traversal_url)
        assert traversal_status in (400, 404), f"path traversal was not rejected: {traversal_status}"
        request(f"{GATEWAY}/media-hls/{camera_id}/index.m3u8", expected=401)
        deadline = time.monotonic() + 45
        segment = b""
        while time.monotonic() < deadline:
            try:
                status, playlist_bytes = request(playlist_url)
            except (TimeoutError, urllib.error.URLError):
                time.sleep(2)
                continue
            if status == 200:
                playlist = playlist_bytes.decode()
                assert "#EXTM3U" in playlist, "upstream HLS playlist must be returned"
                media_uri = next((line.strip() for line in playlist.splitlines() if line.strip() and not line.startswith("#")), None)
                if media_uri:
                    status, segment = request(urllib.parse.urljoin(playlist_url, media_uri))
                    if status == 200 and segment:
                        break
            time.sleep(2)
        assert segment, "timed out waiting for the first protected HLS segment"

        # One Broadcast and one legacy Surveillance API consumer share the same StreamManager session.
        json_request(f"{base}/streams/{camera_id}/start", "POST", expected=200)
        json_request(f"{base}/broadcasts/{broadcast_id}/enabled", "PUT", {"enabled": False})
        request(playlist_url, expected=401)
        status, stream = request(f"{base}/streams/{camera_id}", expected=200)
        assert stream, "legacy Surveillance consumer must retain the shared stream after Broadcast disable"
        json_request(f"{base}/streams/{camera_id}/stop", "POST", expected=204)

        json_request(f"{base}/broadcasts/{broadcast_id}/enabled", "PUT", {"enabled": True})
        fresh = json_request(f"{GATEWAY}/api/playback/{public_id}/token")
        request(urllib.parse.urljoin(GATEWAY + "/", fresh["playlistUrl"]), expected=200)
        json_request(f"{base}/broadcasts/{broadcast_id}/enabled", "PUT", {"enabled": False})
        json_request(f"{base}/broadcasts/{broadcast_id}", "DELETE", expected=204)
        request(f"{GATEWAY}/api/playback/{public_id}/token", expected=404)
        broadcast = None

        print("PASS: camera registration, idempotency, private policy, stable watch/iframe URL, tamper rejection, protected HLS/segments, lease sharing, disable/re-enable revocation, delete")
    finally:
        if broadcast:
            request(f"{base}/broadcasts/{broadcast['id']}", "DELETE", token=MANAGEMENT)
        if camera:
            request(f"{base}/api/v1/cameras/{camera['id']}", "DELETE", token=None)


if __name__ == "__main__":
    main()
