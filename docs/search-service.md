
```txt
POST /api/cameras
       │
       ▼
camera-service
       │
       ▼
Kafka (camera.events)
       │
       ▼
search-service
       │
       ▼
CameraEventsConsumer
       │
       ▼
CameraIndexService
       │
       ▼
log.info(...)
```
