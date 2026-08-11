
```mermaid
flowchart TD
W["Python inference worker"] --> K["Kafka: analytics.events"]
K --> C["AnalyticsEventConsumer"]
C --> V["Валидация и дедупликация"]
V --> DB["PostgreSQL: analytics_events"]
V --> H["Реестр обработчиков"]
H --> N["Уведомления / WebSocket"]
H --> S["Счётчики и статистика"]
H --> M["Создание snapshot / clip"]
C --> D["analytics.events.DLT"]

```
---
### Начальные типы событий

```txt

| Тип                    | Назначение                                        |
| ---------------------- | ------------------------------------------------- |
| `OBJECT_DETECTED`      | Обнаружен человек, автомобиль или другой объект   |
| `LINE_CROSSED`         | Объект пересёк линию                              |
| `ZONE_ENTERED`         | Объект вошёл в зону                               |
| `ZONE_EXITED`          | Объект покинул зону                               |
| `LOITERING_DETECTED`   | Объект слишком долго находится в зоне             |
| `OBJECT_COUNT_CHANGED` | Изменилось количество объектов                    |
| `CAMERA_TAMPERING`     | Камера закрыта, смещена или изображение испорчено |
| `MOTION_DETECTED`      | Обнаружено движение                               |

```

