FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PYTHONPATH=/app/src

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        libgl1 \
        libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY workers/inference-worker/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY workers/inference-worker/src ./src

CMD ["python", "-m", "inference_worker.main"]
