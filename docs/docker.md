
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
