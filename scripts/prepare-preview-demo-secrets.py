#!/usr/bin/env python3
"""Create ignored, least-privilege RTSP secret files from .env.streaming.local."""

from __future__ import annotations

import os
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / ".env.streaming.local"
SECRET_DIR = ROOT / ".secrets" / "preview-demo-people"
SECRET_KEYS = {
    "publish-user": "STREAMING_MEDIA_PUBLISH_USER",
    "publish-password": "STREAMING_MEDIA_PUBLISH_PASSWORD",
    "read-user": "STREAMING_MEDIA_READ_USER",
    "read-password": "STREAMING_MEDIA_READ_PASSWORD",
}


def parse_value(raw: str) -> str:
    value = raw.strip()
    if len(value) >= 2 and value[0] == value[-1] == "'":
        return value[1:-1].replace("\\'", "'").replace("\\\\", "\\")
    if len(value) >= 2 and value[0] == value[-1] == '"':
        body = value[1:-1]
        return (body.replace("\\n", "\n").replace("\\r", "\r")
                    .replace("\\t", "\t").replace('\\"', '"').replace("\\\\", "\\"))
    marker = value.find(" #")
    return value[:marker] if marker >= 0 else value


def read_env() -> dict[str, str]:
    if not ENV_FILE.is_file():
        raise SystemExit("Missing .env.streaming.local; secret preparation stopped.")
    values: dict[str, str] = {}
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in line:
            continue
        key, raw = line.split("=", 1)
        values[key.strip()] = parse_value(raw)
    return values


def write_secret(path: Path, value: str) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags, 0o600)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb", closefd=False) as target:
            target.write(value.encode("utf-8"))
    finally:
        os.close(fd)


def main() -> None:
    values = read_env()
    missing = [env_name for env_name in SECRET_KEYS.values() if not values.get(env_name)]
    if missing:
        raise SystemExit("Missing required RTSP credentials in .env.streaming.local: " + ", ".join(missing))

    SECRET_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(SECRET_DIR, 0o700)
    for filename, env_name in SECRET_KEYS.items():
        write_secret(SECRET_DIR / filename, values[env_name])
    print("Prepared four ignored RTSP secret files with mode 0600; values were not displayed.")


if __name__ == "__main__":
    main()
