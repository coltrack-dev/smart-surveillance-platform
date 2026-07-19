
# Smart Surveillance Platform

Платформа видеонаблюдения на основе событийной архитектуры.

## Архитектура

Краткая схема:

```txt
Camera
  ↓
Gateway
  ↓
Kafka
  ↓
Services

```


## Возможности

- управление камерами
- обработка событий движения
- запись видео
- уведомления
- поиск событий
- аналитика


## Технологический стек

| Область    | Технология               |
| ---------- |--------------------------|
| Язык       | Java 21                  |
| Framework  | Spring Boot 4            |
| Build      | Gradle Multi Module      |
| API        | REST + OpenAPI           |
| Security   | Spring Security + JWT    |
| Broker     | Kafka                    |
| CDC        | Debezium                 |
| DB         | PostgreSQL               |
| Cache      | Redis                    |
| Search     | OpenSearch               |
| Containers | Docker                   |
| Deployment | Kubernetes               |
| Metrics    | Prometheus + Grafana     |
| Testing    | JUnit 5 + Testcontainers |


---
## Модули

### common

- common-events
- common-kafka
- common-logging
  
### Зависимости модулей

```txt

                    common-events
                         |
                         |
              +----------+----------+
              |          |          |
       camera-gateway camera-service recording-service
              |          |          |
              +----------+----------+
                         |
                   common-kafka
                         |
              notification-service
              analytics-service
              search-service


              common-logging
                    |
              все сервисы
```

---
### gateway

- camera-gateway


### services

- camera-service
- recording-service
- notification-service
- analytics-service
- search-service


## Запуск проекта

### Требования

- Java 21
- Docker
- Gradle


### Запуск инфраструктуры

docker compose up


### Сборка

./gradlew build


## Kafka события

| Topic | Назначение |
|---|---|
| camera.events | события камер |
| motion.events | события движения |
| recording.events | события записи |
| notification.events | уведомления |
| analytics.events | |


## API

Swagger:

camera-service:
http://localhost:8081/swagger-ui.html


## Архитектурные решения

- Event-driven architecture
- Kafka message key = cameraId
- Retry + Dead Letter Queue
- Outbox Pattern
- CDC через Debezium
- Idempotent consumers


## Kubernetes

Манифесты находятся:

/k8s

---
### Services

---

1. camera-gateway

Назначение: интеграция с внешними системами.

Показывает:

REST clients
OpenAPI
преобразование форматов партнеров
Kafka Producer

Поток:
```txt
Camera API
    |
    v
camera-gateway
    |
    v
Kafka
(camera.events)
```

---
2. camera-service

Главный сервис.

Отвечает за:

* камеры
* пользователей
* настройки
* состояние камер

Стек:

* Spring MVC
* PostgreSQL
* JPA
* Kafka Consumer

---
3.recording-service
   Отвечает за:

* начало записи
* окончание записи
* хранение метаданных видео

Получает:
```txt
MotionDetectedEvent
```
создает:
```txt
RecordingStartedEvent
```

---
4.notification-service

Получает:
```txt
MotionDetectedEvent
```
Отправляет:

* Email
* Telegram
* Push

Показывает:

* retry
* DLQ
* async processing

---
5.analytics-service
Показывает:

* Kafka Streams
* агрегацию событий
* Redis counters

Например:
```txt
cameraId=10

motions today = 154
```
---
6.search-service

Получает события:
```txt
CameraRegisteredEvent

MotionDetectedEvent
```

Индексирует в:
```txt
OpenSearch
```

---

