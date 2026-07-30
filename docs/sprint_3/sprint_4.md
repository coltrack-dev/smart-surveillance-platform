```txt
FFmpeg segments
 |
MinIO
 |
Playback API
```

---

Этап 1 (сейчас)

✅ Stream lifecycle
⬜ Health monitor
⬜ корректный HLS cleanup
⬜ Kafka event schema

Этап 2

⬜ PostgreSQL stream state
⬜ auto-start через camera.events
⬜ WebSocket status updates

Этап 3

⬜ recording-service
⬜ MinIO/S3 storage
⬜ analytics-service

Этап 4

⬜ WebRTC
⬜ AI detection
