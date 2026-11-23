#!/usr/bin/env python3
"""Bounded HLS request load generator; not a glass-to-glass latency test."""

import argparse
from datetime import datetime
import json
import statistics
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

LOCAL_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def fetch(url, timeout=8):
    started = time.monotonic()
    req = urllib.request.Request(url, headers={"Cache-Control": "no-cache", "Referrer": ""})
    try:
        with LOCAL_OPENER.open(req, timeout=timeout) as response:
            data = response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read()
    except Exception:
        status, data = 0, b""
    return status, data, time.monotonic() - started


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    parser.add_argument("--public-id", required=True)
    parser.add_argument("--viewers", type=int, default=4)
    parser.add_argument("--duration", type=int, default=60)
    args = parser.parse_args()
    if not 1 <= args.viewers <= 16 or not 1 <= args.duration <= 300:
        parser.error("viewers must be 1..16 and duration must be 1..300 seconds")

    base = args.base_url.rstrip("/")
    public_id = urllib.parse.quote(args.public_id, safe="")
    deadline = time.monotonic() + args.duration
    lock = threading.Lock()
    latencies, totals = [], {"requests": 0, "failures": 0, "bytes": 0}

    def record(status, size, elapsed):
        with lock:
            totals["requests"] += 1
            totals["failures"] += status < 200 or status >= 300
            totals["bytes"] += size
            latencies.append(elapsed)

    def viewer():
        authorization = None
        while time.monotonic() < deadline:
            if authorization is None or authorization["expiresAt"] <= time.time() + 60:
                status, data, elapsed = fetch(f"{base}/api/playback/{public_id}/token")
                record(status, len(data), elapsed)
                if status != 200:
                    time.sleep(1)
                    continue
                try:
                    value = json.loads(data)
                    authorization = {
                        "expiresAt": datetime.fromisoformat(value["expiresAt"].replace("Z", "+00:00")).timestamp(),
                        "playlist": urllib.parse.urljoin(base + "/", value["playlistUrl"]),
                    }
                except Exception:
                    record(500, 0, 0)
                    time.sleep(1)
                    continue
            status, playlist_bytes, elapsed = fetch(authorization["playlist"])
            record(status, len(playlist_bytes), elapsed)
            if status == 200:
                refs = [line.strip() for line in playlist_bytes.decode(errors="replace").splitlines()
                        if line.strip() and not line.startswith("#")]
                if refs:
                    segment_url = urllib.parse.urljoin(authorization["playlist"], refs[-1])
                    status, segment, elapsed = fetch(segment_url)
                    record(status, len(segment), elapsed)
            time.sleep(2)

    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=args.viewers) as pool:
        list(pool.map(lambda _: viewer(), range(args.viewers)))
    elapsed = time.monotonic() - started
    ordered = sorted(latencies)
    p50 = statistics.median(ordered) if ordered else 0
    p95 = ordered[min(len(ordered) - 1, int(len(ordered) * .95))] if ordered else 0
    result = {
        "viewers": args.viewers,
        "duration_seconds": round(elapsed, 2),
        "requests": totals["requests"],
        "failures": totals["failures"],
        "failure_rate_percent": round(100 * totals["failures"] / max(1, totals["requests"]), 2),
        "request_latency_p50_ms": round(p50 * 1000, 2),
        "request_latency_p95_ms": round(p95 * 1000, 2),
        "bytes_received": totals["bytes"],
        "throughput_kib_per_second": round(totals["bytes"] / 1024 / max(elapsed, .001), 2),
        "latency_scope": "HTTP request duration; not glass-to-glass playback latency",
    }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
