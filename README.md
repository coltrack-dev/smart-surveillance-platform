
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

- Java 21
- Spring Boot 3
- Gradle Multi Module
- Apache Kafka
- PostgreSQL
- Redis
- OpenSearch
- Debezium
- Docker
- Kubernetes


## Модули

### common

- common-events
- common-kafka
- common-logging


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
