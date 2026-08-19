
### Остановите тестовые FFmpeg-потоки:

```bash
cd ~/projects3/smart-surveillance-platform

docker compose \
-p surveillance-demo \
-f docker-compose.demo.yml \
stop \
rtsp-test-publisher \
rtsp-i287-publisher \
rtsp-file-publisher \
rtsp-file-publisher-2
```

---

### Запуск rtsp 

```bash
docker compose \
-p surveillance-demo \
-f docker-compose.demo.yml \
start mediamtx

```

```bash
docker compose \
  -p surveillance-demo \
  -f docker-compose.demo.yml \
  start \
  rtsp-file-publisher \
  rtsp-file-publisher-2
```

---

### Проверка

```bash
docker ps \
  --filter name=surveillance-mediamtx \
  --filter name=surveillance-rtsp-file-publisher
```

```bash
ffprobe -v error -rtsp_transport tcp \
  -show_entries stream=codec_name,width,height \
  "rtsp://localhost:8554/people"
```

```bash
ffprobe -v error -rtsp_transport tcp \
  -show_entries stream=codec_name,width,height \
  "rtsp://localhost:8554/people-2"
```

---
### Inference worker
```bash

source .venv/bin/activate

python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -r workers/inference-worker/requirements.txt


mkdir -p data/work
mkdir -p data/analytics/snapshots
mkdir -p models

cp ~/.cache/ultralytics/yolo11n.pt models/yolo11n.pt

```

```bash

export PYTHONPATH="$PWD/workers/inference-worker/src"

export KAFKA_ENABLED=true
export KAFKA_BOOTSTRAP_SERVERS=192.168.13.128:9092
export KAFKA_INPUT_TOPICS=recording.events,analytics.jobs
export KAFKA_TOPIC=analytics.events
export KAFKA_CONSUMER_GROUP=inference-workers
export KAFKA_AUTO_OFFSET_RESET=earliest
export KAFKA_MAX_POLL_INTERVAL_MS=86400000

export ANALYTICS_JOB_STATUS_TOPIC=analytics.job-status
export ANALYTICS_WORKER_HEARTBEAT_TOPIC=analytics.worker-heartbeat
export INFERENCE_WORKER_ID=wsl-gpu-01
export INFERENCE_HEARTBEAT_SECONDS=15

export RECORDING_SERVICE_URL=http://192.168.13.128:8095
export INFERENCE_WORK_DIRECTORY="$PWD/data/work"

export ANALYTICS_SNAPSHOTS_DIRECTORY="$PWD/data/analytics/snapshots"
export ANALYTICS_SNAPSHOTS_PUBLIC_PATH=/api/v1/analytics/snapshots
export ANALYTICS_SNAPSHOT_JPEG_QUALITY=75

export YOLO_MODEL="$PWD/models/yolo11n.pt"
export YOLO_DEVICE=cuda:0
export YOLO_CONFIDENCE=0.5
export YOLO_CLASSES=0
```

```bash
cd ~/projects3/smart-surveillance-platform

set -a
source .env
set +a
```

Проверьте доступность сервисов
```bash
nc -vz 192.168.13.128 9092
curl -v http://192.168.13.128:8095/actuator/health
```

```bash
python -m inference_worker.recording_consumer
```

