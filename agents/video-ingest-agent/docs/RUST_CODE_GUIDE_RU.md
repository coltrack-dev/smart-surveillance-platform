# Путеводитель по Rust-коду video-ingest-agent

Документ рассчитан на Java-разработчика, который впервые читает Rust. Начинать
лучше с `model.rs`, затем перейти к `api.rs`, `manager.rs`, `ffmpeg.rs` и только
после этого к `main.rs`.

## 1. Структура программы

В Rust единица сборки называется **crate**. В данном проекте это один бинарный
crate, описанный в `Cargo.toml`.

```text
main.rs     сборка и запуск приложения
config.rs   переменные окружения
model.rs    DTO, enum состояний и JSON-контракт
api.rs      HTTP endpoints Axum
manager.rs  реестр пайплайнов и жизненный цикл FFmpeg
ffmpeg.rs   ffprobe и построение аргументов FFmpeg
metrics.rs  Prometheus-счётчики
```

Запись `mod manager;` в `main.rs` подключает файл `manager.rs`. Запись
`use manager::PipelineManager;` импортирует конкретный тип в текущую область
видимости — это похоже на `import` в Java.

## 2. Владение и заимствование

Главное отличие Rust от Java — отсутствие garbage collector. Компилятор
определяет момент освобождения памяти через правила владения.

```rust
let request: StartPipelineRequest = ...;
supervise(request).await;
```

Здесь значение `request` **перемещено** в `supervise`. После вызова исходная
переменная больше недоступна. Это предотвращает двойное освобождение памяти.

Если функции нужно только прочитать объект, передаётся ссылка:

```rust
validate_request(&request)?;
```

`&request` — неизменяемое заимствование. Владение остаётся у вызывающего кода.
Изменяемая ссылка записывается как `&mut value`; одновременно может существовать
только одна такая ссылка.

`String` владеет строковыми данными, а `&str` только ссылается на строку. Поэтому
конфигурация хранит `String`, а методы, которым нужно лишь чтение, принимают
`&str`.

## 3. Option вместо null

Rust не имеет обычного `null`. Возможное отсутствие значения выражается типом:

```rust
pub pid: Option<u32>
```

Он имеет только два варианта:

```rust
Some(1234)
None
```

Компилятор требует обработать отсутствие значения. Например:

```rust
if let Some(task) = control.task.lock().await.take() {
    let _ = task.await;
}
```

`take()` извлекает значение из Option, оставляя на его месте `None`. Так один
`JoinHandle` невозможно случайно ожидать дважды.

## 4. Result и оператор `?`

Операции, которые могут завершиться ошибкой, возвращают:

```rust
Result<УспешныйТип, ТипОшибки>
```

Например:

```rust
pub async fn stop(...) -> Result<PipelineStatus, ManagerError>
```

Оператор `?` распаковывает успешное значение или немедленно возвращает ошибку:

```rust
let input = Url::parse(&request.rtsp_url).context("invalid rtspUrl")?;
```

Это аналог явной проверки результата с `return`, но без исключений. В `api.rs`
реализация `From<ManagerError> for ApiError` позволяет `?` автоматически
преобразовать ошибку manager в HTTP-ошибку.

## 5. Enum и pattern matching

Rust enum может содержать данные. `Output` описывает два разных контракта:

```rust
pub enum Output {
    Rtsp { url: String },
    Hls { segment_seconds: u16, playlist_segments: u16 },
}
```

`match` извлекает данные и требует обработать каждый вариант:

```rust
match &request.output {
    Output::Rtsp { url } => { /* использовать url */ }
    Output::Hls { segment_seconds, playlist_segments } => { /* HLS */ }
}
```

Это безопаснее цепочки `instanceof`: после добавления третьего варианта
компилятор найдёт все неполные `match`.

## 6. Arc, RwLock и Mutex

`Arc<T>` означает atomically reference-counted pointer. Это совместное владение
одним объектом из нескольких потоков или async-задач:

```rust
Arc<RwLock<HashMap<Uuid, Arc<PipelineControl>>>>
```

Тип читается изнутри наружу:

1. `HashMap` хранит пайплайны по UUID камеры;
2. `RwLock` синхронизирует доступ к map;
3. `Arc` позволяет нескольким копиям manager ссылаться на ту же map.

`RwLock` допускает нескольких readers или одного writer:

```rust
let pipelines = self.pipelines.read().await;
let mut pipelines = self.pipelines.write().await;
```

Полученный guard освобождает lock автоматически при выходе из scope. Явный
`drop(pipelines)` используется, когда lock нужно освободить раньше.

`Mutex` используется для `JoinHandle`, потому что одновременно извлекать и
изменять Option должен только один вызов `stop`.

Это async-locks Tokio. При ожидании они не блокируют поток ОС, в отличие от
обычного `std::sync::Mutex`.

## 7. Future, async и await

`async fn` при вызове возвращает Future. Он выполняется, когда runtime Tokio
его опрашивает:

```rust
let status = manager.start(request).await?;
```

На `.await` текущая задача может приостановиться. Поток Tokio в это время
обрабатывает другие HTTP-запросы или FFmpeg-пайплайны.

`tokio::spawn` создаёт независимую задачу:

```rust
let task = tokio::spawn(async move {
    supervise(config, metrics, request, status, stop_rx).await;
});
```

`move` передаёт задаче владение перечисленными значениями. Это гарантирует, что
они будут жить столько же, сколько фоновый supervisor.

## 8. Канал остановки

Для команды stop используется `tokio::sync::watch`:

```rust
let (stop_tx, stop_rx) = watch::channel(false);
```

Manager хранит sender, supervisor — receiver. Запрос DELETE выполняет:

```rust
stop_tx.send(true)
```

Supervisor одновременно ожидает изменение канала и завершение FFmpeg:

```rust
tokio::select! {
    changed = stop_rx.changed() => { /* остановить FFmpeg */ }
    result = child.wait() => { /* FFmpeg завершился сам */ }
}
```

`select!` продолжает только по первой завершившейся ветке.

## 9. Путь команды запуска

Команда проходит следующие этапы:

1. Axum десериализует JSON в `StartPipelineRequest`.
2. `api::start` проверяет Bearer token.
3. `PipelineManager::start` валидирует URL.
4. Manager проверяет, не занята ли камера.
5. Для HLS очищается и создаётся каталог камеры.
6. Создаются shared status и канал stop.
7. `tokio::spawn` запускает `supervise`.
8. API сразу возвращает HTTP 202 и состояние `STARTING`.
9. Supervisor вызывает ffprobe и строит argv.
10. Запускается дочерний FFmpeg.
11. Статус меняется на `RUNNING`.
12. Supervisor ожидает stop или exit процесса.
13. При ошибке применяется reconnect с задержками 1, 2, 4...30 секунд.

## 10. Почему FFmpeg запускается без shell

Код использует:

```rust
Command::new(ffmpeg_bin).args(&args).spawn()
```

а не `sh -c "ffmpeg ..."`. Каждый элемент `args` передаётся процессу как один
аргумент. Содержимое RTSP URL не может превратить `;`, `$()` или пробел в новую
shell-команду.

`stderr(Stdio::piped())` позволяет читать диагностику. Отдельная async-задача
постоянно опустошает pipe; иначе заполненный системный буфер способен
заблокировать дочерний процесс.

## 11. Serde и JSON

Rust использует snake_case:

```rust
camera_id
```

REST API использует camelCase:

```json
{"cameraId":"..."}
```

Преобразование выполняет:

```rust
#[serde(rename_all = "camelCase")]
```

`#[serde(default)]` разрешает не передавать поле в JSON и подставляет
`Default::default()`. Для `transport` это TCP, для `video_mode` — COPY.

## 12. Что важно проверить перед production

- Не оставлять `AGENT_API_TOKEN` пустым вне локальной разработки.
- Не публиковать порт агента непосредственно в интернет.
- Ограничить права пользователя контейнера к каталогам и устройствам.
- Добавить TLS или mTLS между `stream-service` и удалённым агентом.
- Сохранять desired state либо реализовать reconciliation после рестарта.
- Добавить лимиты числа потоков, CPU, диска и параллельных запусков.
- Позднее заменить force kill на SIGTERM с timeout и SIGKILL fallback.

## 13. Команды для изучения и проверки

```bash
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
cargo run
```

`cargo fmt` форматирует код, `clippy` выполняет расширенный статический анализ,
`test` запускает тестовые модули, а `run` собирает и запускает debug-версию.
