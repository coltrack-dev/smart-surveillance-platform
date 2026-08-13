FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/src

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        libgl1 \
        libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY workers/inference-worker/requirements.txt ./requirements.txt

RUN pip install \
    --no-cache-dir \
    -r requirements.txt

COPY workers/inference-worker/src ./src

RUN mkdir -p \
    /data/work \
    /data/analytics/snapshots \
    /models

CMD ["python", "-m", "inference_worker.recording_consumer"]
