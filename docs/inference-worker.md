
## 1. Архитектура многопоточного worker
```mermaid
flowchart TB
    Kafka["Kafka: команды START/STOP"] --> Main["Main thread"]

    Main --> C1["Capture thread: Camera 1"]
    Main --> C2["Capture thread: Camera 2"]
    Main --> CN["Capture thread: Camera N"]

    C1 --> B1["Latest frame buffer 1"]
    C2 --> B2["Latest frame buffer 2"]
    CN --> BN["Latest frame buffer N"]

    B1 --> Scheduler["Batch scheduler"]
    B2 --> Scheduler
    BN --> Scheduler

    Scheduler --> YOLO["Одна YOLO-модель"]
    YOLO --> Trackers["ByteTrack каждой камеры"]
    Trackers --> Events["Crossing events"]

    Events --> Pool["Snapshot thread pool"]
    Pool --> S3["Wasabi / S3"]
    Pool --> Producer["Kafka producer"]
```

Эта схема показывает главное:

* каждой камере соответствует отдельный поток чтения;
* YOLO-модель общая;
* кадры камер собираются в batch;
* состояния ByteTrack разделены;
* сохранение снимков не блокирует inference.
---
## 2.Потоки внутри одного Python-процесса

```mermaid
flowchart LR
    subgraph Process["Один Python-процесс"]
        Main["Main thread<br/>Kafka commands"]

        subgraph Capture["RTSP capture"]
            T1["Thread 1<br/>Camera A"]
            T2["Thread 2<br/>Camera B"]
        end

        Infer["Inference thread<br/>Batch + YOLO"]

        subgraph Snapshots["Snapshot pool"]
            S1["Worker 1"]
            S2["Worker 2"]
        end
    end

    Main --> T1
    Main --> T2
    T1 --> Infer
    T2 --> Infer
    Infer --> S1
    Infer --> S2
```

Здесь полезно подчеркнуть, что это один процесс и одна модель, хотя внутри работает несколько потоков.
---
## 3. Временная диаграмма выполнения
```mermaid
sequenceDiagram
    participant C1 as Capture Camera 1
    participant C2 as Capture Camera 2
    participant B as Batch Scheduler
    participant Y as YOLO / GPU
    participant T as ByteTrack
    participant S as Snapshot Pool

    par Параллельный захват
        C1->>B: Последний кадр камеры 1
    and
        C2->>B: Последний кадр камеры 2
    end

    B->>Y: Batch из двух кадров
    Y-->>B: Два результата detection
    B->>T: Результат камеры 1
    B->>T: Результат камеры 2
    T-->>S: Событие пересечения линии
    S->>S: JPEG + upload + Kafka publish
```

Она показывает разницу между:

* параллельным получением кадров;
* одним пакетным вызовом YOLO;
* последовательным обновлением tracker;
* асинхронной обработкой снимков.
---

## 4. Работа LatestFrameBuffer
```mermaid
flowchart LR
    F1["Frame 101"] --> Buffer["LatestFrameBuffer"]
    F2["Frame 102"] -->|заменяет 101| Buffer
    F3["Frame 103"] -->|заменяет 102| Buffer
    Buffer -->|take| Inference["Inference получает Frame 103"]
```


Это иллюстрирует, почему worker не накапливает очередь старых кадров. Если камера выдаёт 30 FPS, а аналитика работает с 5 FPS, промежуточные кадры заменяются более свежими.

---

## 5. Состояние отдельных камер
```mermaid
flowchart TB
    Shared["Общие ресурсы<br/>YOLO, GPU, scheduler"]

    Shared --> R1["CameraRuntime A"]
    Shared --> R2["CameraRuntime B"]

    R1 --> T1["ByteTrack A"]
    R1 --> H1["История координат A"]
    R1 --> E1["Stop event A"]
    R1 --> B1["Frame buffer A"]

    R2 --> T2["ByteTrack B"]
    R2 --> H2["История координат B"]
    R2 --> E2["Stop event B"]
    R2 --> B2["Frame buffer B"]
```

Эта схема особенно важна для объяснения изоляции:

* модель общая;
* tracker, буфер, stop_event и история объектов — отдельные для каждой камеры.

---

## 6. Что происходит при отказе одной камеры

```mermaid
sequenceDiagram
    participant A as Camera A thread
    participant B as Camera B thread
    participant I as Inference thread

    A->>I: Frame A
    B--xB: RTSP connection lost
    B->>B: Освободить VideoCapture
    A->>I: Следующий Frame A
    B->>B: Reconnect с задержкой
    A->>I: Следующий Frame A
    B->>I: Соединение восстановлено
```
Так наглядно видно: переподключение Camera B не останавливает Camera A и общий inference.
