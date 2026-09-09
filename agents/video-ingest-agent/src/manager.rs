//! Жизненный цикл видеопайплайнов и дочерних FFmpeg-процессов.
//!
//! Центральный модуль агента. `PipelineManager` хранит реестр камер, а для
//! каждой камеры отдельная Tokio task выполняет `supervise`: запускает FFmpeg,
//! ждёт его завершения и при необходимости делает reconnect.

use std::{
    collections::{HashMap, VecDeque},
    process::{ExitStatus, Stdio},
    sync::Arc,
    time::Duration,
};

use anyhow::{Context, Result};
use tokio::{
    fs,
    io::{AsyncBufReadExt, BufReader},
    process::Command,
    sync::{oneshot, watch, Mutex, RwLock},
    task::JoinHandle,
    time::sleep,
};
use tracing::{info, warn};
use uuid::Uuid;

use crate::{
    config::Config,
    ffmpeg,
    metrics::Metrics,
    model::{Output, PipelineState, PipelineStatus, StartPipelineRequest},
};

/// Ошибки, которые HTTP-слой может однозначно преобразовать в status code.
#[derive(Debug, thiserror::Error)]
pub enum ManagerError {
    #[error("pipeline for camera {0} already exists")]
    AlreadyExists(Uuid),
    #[error("pipeline for camera {0} was not found")]
    NotFound(Uuid),
    #[error("invalid pipeline: {0}")]
    Invalid(String),
}

/// Все управляющие объекты одного пайплайна.
///
/// Структура приватная: внешний код получает только безопасный `PipelineStatus`.
struct PipelineControl {
    /// RwLock допускает много одновременных читателей статуса или одного writer.
    /// Arc позволяет manager и supervisor владеть ссылками на один статус.
    status: Arc<RwLock<PipelineStatus>>,
    /// watch-канал хранит последнее bool-значение. `true` — команда остановки.
    stop_tx: watch::Sender<bool>,
    /// JoinHandle представляет запущенную async-задачу supervisor.
    /// Option нужен, чтобы извлечь handle ровно один раз методом `take()`.
    task: Mutex<Option<JoinHandle<()>>>,
    /// Для RTSP-output равно None; для HLS указывает строго на каталог камеры.
    cleanup_dir: Option<std::path::PathBuf>,
}

/// Результат ожидания реальной готовности только что запущенного FFmpeg.
enum StartupOutcome {
    /// FFmpeg сообщил progress=continue: кадры уже проходят через output.
    Ready,
    /// Получена команда остановки или закрыт управляющий канал.
    Stopped,
    /// Процесс завершился до появления первого output progress.
    Exited(Option<ExitStatus>),
    /// Процесс существует, но не начал обрабатывать кадры за допустимое время.
    TimedOut,
}

/// Потокобезопасный реестр всех активных пайплайнов данного агента.
#[derive(Clone)]
pub struct PipelineManager {
    config: Config,
    metrics: Metrics,
    // Аналог ConcurrentHashMap<UUID, PipelineControl> из Java, но доступ явно
    // разделён на read().await и write().await.
    pipelines: Arc<RwLock<HashMap<Uuid, Arc<PipelineControl>>>>,
}

impl PipelineManager {
    pub fn new(config: Config, metrics: Metrics) -> Self {
        Self {
            config,
            metrics,
            pipelines: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn start(
        &self,
        request: StartPipelineRequest,
    ) -> Result<PipelineStatus, ManagerError> {
        // Проверяем схему URL до захвата ресурсов и запуска фоновой задачи.
        validate_request(&request).map_err(|e| ManagerError::Invalid(e.to_string()))?;

        // `mut` требуется, потому что ниже вызывается HashMap::insert.
        // Write guard автоматически освободит lock при выходе из scope/drop.
        let mut pipelines = self.pipelines.write().await;
        if let Some(existing) = pipelines.get(&request.camera_id) {
            let status = existing.status.read().await.clone();
            // Повтор той же commandId идемпотентен: возвращаем существующий
            // результат. Другая команда для занятой камеры получает Conflict.
            if status.command_id == request.command_id {
                return Ok(status);
            }
            return Err(ManagerError::AlreadyExists(request.camera_id));
        }

        // `if let` удобен, когда интересует только один вариант enum.
        // `&request.output` — заимствование: request остаётся целым для supervisor.
        let cleanup_dir = if let Output::Hls { .. } = &request.output {
            let directory = self
                .config
                .data_dir
                .join("hls")
                .join(request.camera_id.to_string());
            // Удаляем старый playlist/segments, иначе новый поток мог бы
            // продолжить устаревший HLS playlist.
            if directory.exists() {
                fs::remove_dir_all(&directory).await.map_err(|e| {
                    ManagerError::Invalid(format!("cannot clean HLS directory: {e}"))
                })?;
            }
            fs::create_dir_all(&directory)
                .await
                .map_err(|e| ManagerError::Invalid(format!("cannot create HLS directory: {e}")))?;
            Some(directory)
        } else {
            None
        };

        // Новый статус одновременно читается API и изменяется supervisor task.
        let status = Arc::new(RwLock::new(PipelineStatus::starting(
            self.config.agent_id.clone(),
            &request,
        )));
        let initial_status = status.read().await.clone();
        // Канал разделяется на Sender в control и Receiver в supervisor.
        let (stop_tx, stop_rx) = watch::channel(false);
        let control = Arc::new(PipelineControl {
            status: status.clone(),
            stop_tx,
            task: Mutex::new(None),
            cleanup_dir,
        });
        pipelines.insert(request.camera_id, control.clone());
        self.metrics.active_pipelines.inc();
        // Освобождаем write lock до tokio::spawn и дальнейших await. Явный drop
        // сокращает критическую секцию и позволяет другим HTTP-запросам работать.
        drop(pipelines);

        let config = self.config.clone();
        let metrics = self.metrics.clone();
        // `async move` передаёт владение config/request/status/stop_rx фоновой
        // задаче. Без move ссылки могли бы пережить stack frame метода start.
        let task = tokio::spawn(async move {
            supervise(config, metrics, request, status, stop_rx).await;
        });
        *control.task.lock().await = Some(task);

        Ok(initial_status)
    }

    pub async fn stop(&self, camera_id: Uuid) -> Result<PipelineStatus, ManagerError> {
        // Извлекаем control из map, чтобы получить владение Arc и продолжить
        // остановку без удержания lock. Следствие текущего MVP: параллельный
        // start той же камеры уже сможет пройти; в production-версии лучше
        // оставлять запись со статусом STOPPING до завершения дочернего процесса.
        let control = self
            .pipelines
            .write()
            .await
            .remove(&camera_id)
            .ok_or(ManagerError::NotFound(camera_id))?;
        self.metrics.active_pipelines.dec();

        {
            let mut status = control.status.write().await;
            status.state = PipelineState::Stopping;
            status.touch();
        }
        // Ошибка send означает, что supervisor уже завершился. Для stop это не
        // критично, поэтому Result намеренно игнорируется через `let _ =`.
        let _ = control.stop_tx.send(true);
        if let Some(task) = control.task.lock().await.take() {
            // Ожидаем supervisor: после этого дочерний FFmpeg уже завершён/reaped.
            let _ = task.await;
        }
        if let Some(directory) = &control.cleanup_dir {
            if let Err(error) = fs::remove_dir_all(directory).await {
                if error.kind() != std::io::ErrorKind::NotFound {
                    warn!(camera_id = %camera_id, error = %error, "cannot clean HLS directory");
                }
            }
        }
        let final_status = {
            let mut status = control.status.write().await;
            status.state = PipelineState::Stopped;
            status.pid = None;
            status.touch();
            status.clone()
        };
        Ok(final_status)
    }

    pub async fn get(&self, camera_id: Uuid) -> Result<PipelineStatus, ManagerError> {
        let pipelines = self.pipelines.read().await;
        let control = pipelines
            .get(&camera_id)
            .ok_or(ManagerError::NotFound(camera_id))?;
        let current_status = control.status.read().await.clone();
        Ok(current_status)
    }

    pub async fn list(&self) -> Vec<PipelineStatus> {
        // Сначала клонируем Arc-контролы и отпускаем lock всей HashMap. Затем
        // читаем статусы по одному, не блокируя start/stop на длительное время.
        let controls: Vec<_> = self.pipelines.read().await.values().cloned().collect();
        let mut result = Vec::with_capacity(controls.len());
        for control in controls {
            result.push(control.status.read().await.clone());
        }
        result.sort_by_key(|item| item.camera_id);
        result
    }

    pub async fn shutdown(&self) {
        // Нельзя итерировать HashMap под read lock и одновременно вызывать stop,
        // которому нужен write lock. Поэтому сначала копируем UUID в Vec.
        let camera_ids: Vec<_> = self.pipelines.read().await.keys().copied().collect();
        for camera_id in camera_ids {
            let _ = self.stop(camera_id).await;
        }
    }
}

/// Фоновый supervisor ровно одного camera pipeline.
async fn supervise(
    config: Config,
    metrics: Metrics,
    request: StartPipelineRequest,
    status: Arc<RwLock<PipelineStatus>>,
    mut stop_rx: watch::Receiver<bool>,
) {
    // Ошибка probe не запрещает запуск: некоторые NVR нестабильно отвечают
    // ffprobe, хотя последующий длительный FFmpeg успешно подключается.
    let detected_codec = match ffmpeg::probe(
        &config.ffprobe_bin,
        &request,
        config.probe_timeout,
    )
    .await
    {
        Ok(probe) => {
            let codec = probe.codec.clone();
            status.write().await.probe = Some(probe);
            Some(codec)
        }
        Err(error) => {
            let message = error.to_string();
            warn!(camera_id = %request.camera_id, error = %message, "RTSP probe failed");
            let mut current = status.write().await;
            current.last_error = Some(message);
            current.touch();
            None
        }
    };

    let args = match ffmpeg::build_args(
        &request,
        &config.data_dir,
        detected_codec.as_deref(),
    ) {
        Ok(args) => args,
        Err(error) => {
            fail_status(&status, error.to_string()).await;
            return;
        }
    };

    // mutable backoff увеличивается после каждой неудачи: 1, 2, 4...30 секунд.
    let mut backoff = Duration::from_secs(1);
    loop {
        // borrow читает текущее значение watch-канала без ожидания изменения.
        if *stop_rx.borrow() {
            stopped_status(&status).await;
            return;
        }

        info!(
            camera_id = %request.camera_id,
            source = %ffmpeg::redact_url(&request.rtsp_url),
            "starting FFmpeg pipeline"
        );

        // Command запускает программу напрямую, без `/bin/sh -c`.
        let mut child = match Command::new(&config.ffmpeg_bin)
            .args(&args)
            .stdin(Stdio::null())
            // stdout содержит только machine-readable данные `-progress pipe:1`.
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            // Защита от orphan process: если объект Child неожиданно потерян,
            // Tokio посылает дочернему процессу kill.
            .kill_on_drop(true)
            .spawn()
        {
            Ok(child) => child,
            Err(error) => {
                metrics.process_failures.inc();
                let message = format!("failed to spawn FFmpeg: {error}");
                if !request.reconnect {
                    fail_status(&status, message).await;
                    return;
                }
                reconnect_status(&status, message).await;
                if wait_or_stop(&mut stop_rx, backoff).await {
                    stopped_status(&status).await;
                    return;
                }
                backoff = next_backoff(backoff);
                // `continue` немедленно начинает следующую итерацию loop.
                continue;
            }
        };

        metrics.process_starts.inc();
        let pid = child.id();
        {
            let mut current = status.write().await;
            // Наличие PID ещё не доказывает, что FFmpeg открыл output. Поэтому
            // до первой progress-записи pipeline остаётся в STARTING.
            current.state = PipelineState::Starting;
            current.pid = pid;
            current.output_url = ffmpeg::output_url(&request);
            current.last_error = None;
            current.touch();
        }

        // Храним последние диагностические строки отдельно от logger, чтобы
        // после exit они попали в наблюдаемый PipelineStatus.lastError.
        let recent_stderr = Arc::new(Mutex::new(VecDeque::<String>::with_capacity(5)));

        // `take()` перемещает stderr из Option внутри Child, оставляя None.
        // Отдельная task читает pipe, чтобы его буфер не заполнился и не
        // заблокировал FFmpeg. URL с credentials очищается перед логированием.
        let stderr_task = child.stderr.take().map(|stderr| {
            let camera_id = request.camera_id;
            let source_url = request.rtsp_url.clone();
            let recent_stderr = recent_stderr.clone();
            tokio::spawn(async move {
                let mut lines = BufReader::new(stderr).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    let safe_line = ffmpeg::sanitize_message(&line, &source_url);
                    let mut recent = recent_stderr.lock().await;
                    if recent.len() == 5 {
                        recent.pop_front();
                    }
                    recent.push_back(safe_line.clone());
                    drop(recent);
                    warn!(camera_id = %camera_id, ffmpeg = %safe_line, "FFmpeg diagnostic");
                }
            })
        });

        // `oneshot` передаёт ровно одно подтверждение от progress-reader.
        let (ready_tx, mut ready_rx) = oneshot::channel();
        let stdout_task = child.stdout.take().map(|stdout| {
            tokio::spawn(async move {
                let mut lines = BufReader::new(stdout).lines();
                let mut ready_tx = Some(ready_tx);
                while let Ok(Some(line)) = lines.next_line().await {
                    if line == "progress=continue" {
                        if let Some(sender) = ready_tx.take() {
                            let _ = sender.send(());
                        }
                    }
                }
            })
        });

        // FFmpeg считается готовым только после обработки первых кадров.
        let startup = tokio::select! {
            ready = &mut ready_rx => {
                if ready.is_ok() {
                    StartupOutcome::Ready
                } else {
                    StartupOutcome::Exited(child.wait().await.ok())
                }
            }
            changed = stop_rx.changed() => {
                if changed.is_err() || *stop_rx.borrow() {
                    if let Err(error) = terminate_child(&mut child).await {
                        warn!(camera_id = %request.camera_id, error = %error, "failed to terminate FFmpeg cleanly");
                    }
                    StartupOutcome::Stopped
                } else {
                    StartupOutcome::Exited(child.wait().await.ok())
                }
            }
            result = child.wait() => StartupOutcome::Exited(result.ok()),
            _ = sleep(config.ready_timeout) => {
                if let Err(error) = terminate_child(&mut child).await {
                    warn!(camera_id = %request.camera_id, error = %error, "failed to terminate unready FFmpeg");
                }
                StartupOutcome::TimedOut
            }
        };

        let (exit_result, startup_error, stop_received) = match startup {
            StartupOutcome::Ready => {
                {
                    let mut current = status.write().await;
                    current.state = PipelineState::Running;
                    current.last_error = None;
                    current.touch();
                }
                // После успешного запуска следующая авария снова начинает
                // reconnect с одной секунды, а не с накопленных 30 секунд.
                backoff = Duration::from_secs(1);
                let (exit_result, stop_received) = tokio::select! {
                    changed = stop_rx.changed() => {
                        if changed.is_err() || *stop_rx.borrow() {
                            if let Err(error) = terminate_child(&mut child).await {
                                warn!(camera_id = %request.camera_id, error = %error, "failed to terminate FFmpeg cleanly");
                            }
                            (None, true)
                        } else {
                            (child.wait().await.ok(), false)
                        }
                    }
                    result = child.wait() => (result.ok(), false),
                };
                (exit_result, None, stop_received)
            }
            StartupOutcome::Stopped => (None, None, true),
            StartupOutcome::Exited(exit) => (exit, None, false),
            StartupOutcome::TimedOut => (
                None,
                Some(format!(
                    "FFmpeg did not produce output progress within {} seconds",
                    config.ready_timeout.as_secs()
                )),
                false,
            ),
        };

        // После завершения процесса дочитываем pipes, включая последние строки
        // stderr, вместо немедленного abort diagnostic task.
        if let Some(task) = stderr_task {
            let _ = task.await;
        }
        if let Some(task) = stdout_task {
            let _ = task.await;
        }

        if stop_received || *stop_rx.borrow() {
            stopped_status(&status).await;
            return;
        }

        metrics.process_failures.inc();
        let mut message = match startup_error {
            Some(error) => error,
            None => match exit_result {
                Some(exit) => format!("FFmpeg exited with {exit}"),
                None => "FFmpeg wait failed".into(),
            },
        };
        let diagnostic = recent_stderr
            .lock()
            .await
            .iter()
            .cloned()
            .collect::<Vec<_>>()
            .join(" | ");
        if !diagnostic.is_empty() {
            message.push_str(": ");
            message.push_str(&diagnostic);
        }
        if !request.reconnect {
            fail_status(&status, message).await;
            return;
        }

        metrics.reconnects.inc();
        reconnect_status(&status, message).await;
        if wait_or_stop(&mut stop_rx, backoff).await {
            stopped_status(&status).await;
            return;
        }
        backoff = next_backoff(backoff);
    }
}

async fn terminate_child(child: &mut tokio::process::Child) -> Result<()> {
    // start_kill инициирует завершение, а wait обязательно забирает exit status.
    // Без wait в Unix мог бы временно остаться zombie process.
    child.start_kill().context("cannot send kill signal")?;
    child.wait().await.context("cannot reap FFmpeg process")?;
    Ok(())
}

async fn wait_or_stop(stop_rx: &mut watch::Receiver<bool>, duration: Duration) -> bool {
    // Во время backoff агент остаётся отзывчивым к DELETE /pipelines/{id}.
    tokio::select! {
        _ = sleep(duration) => false,
        changed = stop_rx.changed() => changed.is_err() || *stop_rx.borrow(),
    }
}

fn next_backoff(current: Duration) -> Duration {
    // saturating_mul не допускает integer overflow, min ограничивает задержку.
    current.saturating_mul(2).min(Duration::from_secs(30))
}

async fn reconnect_status(status: &RwLock<PipelineStatus>, error: String) {
    // Guard от write().await освобождается автоматически в конце функции (RAII).
    let mut current = status.write().await;
    current.state = PipelineState::Reconnecting;
    current.pid = None;
    current.restart_count += 1;
    current.last_error = Some(error);
    current.touch();
}

async fn fail_status(status: &RwLock<PipelineStatus>, error: String) {
    let mut current = status.write().await;
    current.state = PipelineState::Failed;
    current.pid = None;
    current.last_error = Some(error);
    current.touch();
}

async fn stopped_status(status: &RwLock<PipelineStatus>) {
    let mut current = status.write().await;
    current.state = PipelineState::Stopped;
    current.pid = None;
    current.touch();
}

fn validate_request(request: &StartPipelineRequest) -> Result<()> {
    // Parse проверяет синтаксис, а отдельная проверка scheme запрещает случайно
    // передать http/file URL туда, где ожидается RTSP-источник.
    let input = url::Url::parse(&request.rtsp_url).context("invalid rtspUrl")?;
    if input.scheme() != "rtsp" && input.scheme() != "rtsps" {
        anyhow::bail!("rtspUrl must use rtsp or rtsps scheme");
    }
    if let Output::Rtsp { url } = &request.output {
        let output = url::Url::parse(url).context("invalid RTSP output URL")?;
        if output.scheme() != "rtsp" && output.scheme() != "rtsps" {
            anyhow::bail!("RTSP output must use rtsp or rtsps scheme");
        }
    }
    Ok(())
}
