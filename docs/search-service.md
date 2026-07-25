
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

---
### синхронизация данных

```txt

                 POST /api/cameras
                         |
                         v
                 camera-service
                         |
              save CameraEntity
                         |
                         v
                    PostgreSQL
                         |
                         v
              Kafka topic camera.events
                         |
                         v
                 search-service
                         |
                         v
             CameraEventsConsumer
                         |
                         v
              CameraIndexService
```
---

```txt
camera-service
      |
      |  CameraRegisteredEvent
      v
 Kafka topic
 camera.events
      |
      v
search-service
      |
      | ошибка обработки
      v
 retry 3 раза
      |
      v
 camera.events.DLT
```
