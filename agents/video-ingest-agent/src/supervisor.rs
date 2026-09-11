//! Supervisor одного FFmpeg-пайплайна: запуск, readiness, watchdog и reconnect.

use std::{
    collections::VecDeque,
    process::{ExitStatus, Stdio},
    sync::Arc,
    time::Duration,
};

use anyhow::{Context, Result};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    process::{ChildStdin, Command},
    sync::{mpsc, watch, Mutex, RwLock, Semaphore},
    time::{sleep, Instant},
};
use tracing::{info, warn};

use crate::{
    config::Config,
    ffmpeg,
    metrics::Metrics,
    model::{Output, PipelineState, PipelineStatus, RtspTransport, StartPipelineRequest},
};

/// Две последовательные ошибки отличают потерю output от единичного сетевого
/// сбоя ffprobe, который не должен прерывать исправный пользовательский поток.
const OUTPUT_HEALTH_FAILURE_THRESHOLD: u8 = 2;

/// Результат ожидания реальной готовности только что запущенного FFmpeg.
enum StartupOutcome {
    /// FFmpeg сообщил первый progress, после которого проверяется output.
    Progress(ProgressUpdate),
    /// FFmpeg пишет кадры, а опубликованный output доступен читателю.
    Ready(ProgressUpdate),
    /// Получена команда остановки или закрыт управляющий канал.
    Stopped,
    /// Процесс завершился до появления первого output progress.
    Exited(Option<ExitStatus>),
    /// Процесс существует, но не начал обрабатывать кадры за допустимое время.
    TimedOut,
    /// MediaMTX не отдал видеопоток за отведённое время.
    OutputTimedOut(String),
}

/// Результат наблюдения за FFmpeg после успешного старта.
enum RunningOutcome {
    /// Получена команда штатной остановки.
    Stopped,
    /// Дочерний процесс завершился сам.
    Exited(Option<ExitStatus>),
    /// Процесс жив, но временная позиция output перестала изменяться.
    Stalled,
    /// FFmpeg продолжает работать, но опубликованный RTSP больше не читается.
    OutputUnavailable(String),
}

/// Один завершённый блок machine-readable `-progress` от FFmpeg.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct ProgressUpdate {
    output_time_ms: Option<u64>,
}

/// Накапливает отдельные строки одного progress-блока FFmpeg.
#[derive(Default)]
struct ProgressParser {
    output_time_ms: Option<u64>,
}

impl ProgressParser {
    fn accept(&mut self, line: &str) -> Option<ProgressUpdate> {
        // Современный FFmpeg печатает `out_time_us` в микросекундах. Деление
        // здесь приводит значение к миллисекундам публичного API агента.
        if let Some(value) = line.strip_prefix("out_time_us=") {
            self.output_time_ms = value.parse::<u64>().ok().map(|value| value / 1_000);
            return None;
        }
        if line == "progress=continue" {
            return Some(ProgressUpdate {
                output_time_ms: self.output_time_ms,
            });
        }
        None
    }
}
/// Фоновый supervisor ровно одного camera pipeline.
pub(crate) async fn supervise(
    config: Config,
    metrics: Metrics,
    request: StartPipelineRequest,
    status: Arc<RwLock<PipelineStatus>>,
    mut stop_rx: watch::Receiver<bool>,
    probe_slots: Arc<Semaphore>,
) {
    // Ошибка probe не запрещает запуск: некоторые NVR нестабильно отвечают
    // ffprobe, хотя последующий длительный FFmpeg успешно подключается.
    let detected_codec = match probe_input(&config, &request, &probe_slots).await {
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
            // stdin остаётся pipe: при штатной остановке FFmpeg сначала получает
            // команду `q`, а принудительный kill используется только как fallback.
            .stdin(Stdio::piped())
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

        // Child::wait() в Tokio закрывает stdin дочернего процесса при первом
        // polling. В supervisor wait постоянно участвует в select!, поэтому
        // сохраняем pipe отдельно: так FFmpeg не получает преждевременный EOF,
        // а terminate_child действительно может отправить штатную команду q.
        let mut child_stdin = child.stdin.take();

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
            current.last_progress_at_epoch_ms = None;
            current.last_output_time_ms = None;
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

        // Канал передаёт каждый завершённый progress-блок supervisor-у. В отличие
        // от прежнего oneshot первая запись подтверждает старт, а последующие
        // служат heartbeat и позволяют обнаружить живой, но зависший FFmpeg.
        let (progress_tx, mut progress_rx) = mpsc::unbounded_channel();
        let stdout_task = child.stdout.take().map(|stdout| {
            tokio::spawn(async move {
                let mut lines = BufReader::new(stdout).lines();
                let mut parser = ProgressParser::default();
                while let Ok(Some(line)) = lines.next_line().await {
                    if let Some(update) = parser.accept(&line) {
                        if progress_tx.send(update).is_err() {
                            break;
                        }
                    }
                }
            })
        });

        // FFmpeg считается готовым только после обработки первых кадров.
        let startup = tokio::select! {
            progress = progress_rx.recv() => {
                if let Some(progress) = progress {
                    StartupOutcome::Progress(progress)
                } else {
                    StartupOutcome::Exited(child.wait().await.ok())
                }
            }
            changed = stop_rx.changed() => {
                if changed.is_err() || *stop_rx.borrow() {
                    if let Err(error) = terminate_child(&mut child, &mut child_stdin).await {
                        warn!(camera_id = %request.camera_id, error = %error, "failed to terminate FFmpeg cleanly");
                    }
                    StartupOutcome::Stopped
                } else {
                    StartupOutcome::Exited(child.wait().await.ok())
                }
            }
            result = child.wait() => StartupOutcome::Exited(result.ok()),
            _ = sleep(config.ready_timeout) => {
                if let Err(error) = terminate_child(&mut child, &mut child_stdin).await {
                    warn!(camera_id = %request.camera_id, error = %error, "failed to terminate unready FFmpeg");
                }
                StartupOutcome::TimedOut
            }
        };

        // Первый progress доказывает работу FFmpeg, но RTSP-сервер мог ещё не
        // зарегистрировать опубликованный path. Для RTSP-output отдельно
        // открываем поток через ffprobe до публикации состояния RUNNING.
        let startup = match startup {
            StartupOutcome::Progress(first_progress) => match &request.output {
                Output::Rtsp { url } => {
                    let readiness = wait_for_rtsp_output(&config, url, &probe_slots);
                    tokio::pin!(readiness);
                    tokio::select! {
                        result = &mut readiness => {
                            match result {
                                Ok(()) => {
                                    metrics.output_readiness_successes.inc();
                                    StartupOutcome::Ready(first_progress)
                                }
                                Err(error) => {
                                    metrics.output_readiness_failures.inc();
                                    if let Err(kill_error) = terminate_child(&mut child, &mut child_stdin).await {
                                        warn!(camera_id = %request.camera_id, error = %kill_error, "failed to terminate FFmpeg with unavailable output");
                                    }
                                    StartupOutcome::OutputTimedOut(error.to_string())
                                }
                            }
                        }
                        changed = stop_rx.changed() => {
                            if changed.is_err() || *stop_rx.borrow() {
                                if let Err(error) = terminate_child(&mut child, &mut child_stdin).await {
                                    warn!(camera_id = %request.camera_id, error = %error, "failed to terminate FFmpeg cleanly");
                                }
                                StartupOutcome::Stopped
                            } else {
                                StartupOutcome::Exited(child.wait().await.ok())
                            }
                        }
                        result = child.wait() => StartupOutcome::Exited(result.ok()),
                    }
                }
                Output::Hls { .. } => StartupOutcome::Ready(first_progress),
            },
            outcome => outcome,
        };

        let (exit_result, startup_error, stop_received) = match startup {
            StartupOutcome::Ready(first_progress) => {
                {
                    let mut current = status.write().await;
                    current.state = PipelineState::Running;
                    current.last_error = None;
                    current.record_progress(first_progress.output_time_ms);
                }
                metrics.record_progress();
                // После успешного запуска следующая авария снова начинает
                // reconnect с одной секунды, а не с накопленных 30 секунд.
                backoff = Duration::from_secs(1);

                let mut last_output_time_ms = first_progress.output_time_ms;
                let stall_timer = sleep(config.output_stall_timeout);
                tokio::pin!(stall_timer);
                let mut progress_open = true;

                // Progress подтверждает движение FFmpeg, но не гарантирует, что
                // MediaMTX по-прежнему отдаёт опубликованный path читателям.
                // Отдельная task периодически открывает output через ffprobe и
                // сообщает результат supervisor-у, не блокируя stop/child.wait.
                let (output_health_tx, mut output_health_rx) = mpsc::unbounded_channel();
                let output_health_task = match &request.output {
                    Output::Rtsp { url } => {
                        let ffprobe_bin = config.ffprobe_bin.clone();
                        let output_url = url.clone();
                        let interval = config.output_health_interval;
                        let timeout = config.output_ready_timeout;
                        let probe_slots = probe_slots.clone();
                        Some(tokio::spawn(async move {
                            monitor_rtsp_output(
                                ffprobe_bin,
                                output_url,
                                interval,
                                timeout,
                                probe_slots,
                                output_health_tx,
                            )
                            .await;
                        }))
                    }
                    Output::Hls { .. } => None,
                };
                let mut output_health_open = output_health_task.is_some();
                let mut consecutive_output_health_failures = 0u8;

                let running = loop {
                    tokio::select! {
                        changed = stop_rx.changed() => {
                            if changed.is_err() || *stop_rx.borrow() {
                                if let Err(error) = terminate_child(&mut child, &mut child_stdin).await {
                                    warn!(camera_id = %request.camera_id, error = %error, "failed to terminate FFmpeg cleanly");
                                }
                                break RunningOutcome::Stopped;
                            }
                        }
                        result = child.wait() => {
                            break RunningOutcome::Exited(result.ok());
                        }
                        progress = progress_rx.recv(), if progress_open => {
                            if let Some(progress) = progress {
                                {
                                    let mut current = status.write().await;
                                    current.record_progress(progress.output_time_ms);
                                }
                                metrics.record_progress();

                                // Сам факт строки progress недостаточен: зависший
                                // muxer может повторять прежнюю временную позицию.
                                // Таймер сбрасывается только при движении media time.
                                if output_advanced(last_output_time_ms, progress.output_time_ms) {
                                    last_output_time_ms = progress.output_time_ms;
                                    stall_timer.as_mut().reset(
                                        Instant::now() + config.output_stall_timeout
                                    );
                                }
                            } else {
                                progress_open = false;
                            }
                        }
                        health = output_health_rx.recv(), if output_health_open => {
                            match health {
                                Some(Ok(())) => {
                                    metrics.output_health_successes.inc();
                                    consecutive_output_health_failures = 0;
                                }
                                Some(Err(error)) => {
                                    metrics.output_health_failures.inc();
                                    consecutive_output_health_failures += 1;
                                    warn!(
                                        camera_id = %request.camera_id,
                                        consecutive_failures = consecutive_output_health_failures,
                                        error = %error,
                                        "published RTSP output health check failed"
                                    );
                                    if consecutive_output_health_failures
                                        >= OUTPUT_HEALTH_FAILURE_THRESHOLD
                                    {
                                        if let Err(kill_error) = terminate_child(&mut child, &mut child_stdin).await {
                                            warn!(camera_id = %request.camera_id, error = %kill_error, "failed to terminate FFmpeg with unavailable output");
                                        }
                                        break RunningOutcome::OutputUnavailable(error);
                                    }
                                }
                                None => output_health_open = false,
                            }
                        }
                        _ = &mut stall_timer => {
                            metrics.output_stalls.inc();
                            if let Err(error) = terminate_child(&mut child, &mut child_stdin).await {
                                warn!(camera_id = %request.camera_id, error = %error, "failed to terminate stalled FFmpeg");
                            }
                            break RunningOutcome::Stalled;
                        }
                    }
                };

                // При завершении running-loop незаконченный ffprobe больше не
                // нужен. abort безопасен: probe использует kill_on_drop(true),
                // поэтому отдельный дочерний процесс не останется orphan.
                if let Some(task) = output_health_task {
                    task.abort();
                    let _ = task.await;
                }

                match running {
                    RunningOutcome::Stopped => (None, None, true),
                    RunningOutcome::Exited(exit) => (exit, None, false),
                    RunningOutcome::Stalled => (
                        None,
                        Some(format!(
                            "FFmpeg output time did not advance for {} seconds",
                            config.output_stall_timeout.as_secs()
                        )),
                        false,
                    ),
                    RunningOutcome::OutputUnavailable(error) => {
                        (None, Some(error), false)
                    }
                }
            }
            StartupOutcome::Progress(_) => unreachable!("output readiness must be resolved"),
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
            StartupOutcome::OutputTimedOut(error) => (None, Some(error), false),
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

async fn monitor_rtsp_output(
    ffprobe_bin: String,
    output_url: String,
    interval: Duration,
    probe_timeout: Duration,
    probe_slots: Arc<Semaphore>,
    updates: mpsc::UnboundedSender<Result<(), String>>,
) {
    loop {
        // Первая проверка выполняется через полный interval: output уже был
        // подтверждён startup-readiness непосредственно перед RUNNING.
        sleep(interval).await;
        let _permit = probe_slots.acquire().await.expect("probe semaphore closed");
        let result = ffmpeg::probe_rtsp_url(
            &ffprobe_bin,
            &output_url,
            &RtspTransport::Tcp,
            probe_timeout,
        )
        .await
        .map(|_| ())
        .map_err(|error| {
            format!(
                "published RTSP output {} became unavailable: {}",
                ffmpeg::redact_url(&output_url),
                error
            )
        });
        if updates.send(result).is_err() {
            return;
        }
    }
}

async fn wait_for_rtsp_output(
    config: &Config,
    output_url: &str,
    probe_slots: &Semaphore,
) -> Result<()> {
    // Каждый probe получает всё оставшееся время общего deadline.
    // Быстрый ответ path-not-found позволяет повторить попытку, а уже открытый
    // RTSP-поток не уничтожается раньше получения параметров видеодорожки.
    let deadline = Instant::now() + config.output_ready_timeout;
    let mut last_error = None;
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        let attempt_timeout = remaining;
        let _permit = probe_slots.acquire().await.expect("probe semaphore closed");
        match ffmpeg::probe_rtsp_url(
            &config.ffprobe_bin,
            output_url,
            &RtspTransport::Tcp,
            attempt_timeout,
        )
        .await
        {
            Ok(_) => return Ok(()),
            Err(error) => last_error = Some(error.to_string()),
        }

        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        sleep(remaining.min(Duration::from_millis(250))).await;
    }

    anyhow::bail!(
        "published RTSP output {} was not ready within {} seconds: {}",
        ffmpeg::redact_url(output_url),
        config.output_ready_timeout.as_secs(),
        last_error.unwrap_or_else(|| "ffprobe did not complete".into())
    )
}

async fn probe_input(
    config: &Config,
    request: &StartPipelineRequest,
    probe_slots: &Semaphore,
) -> Result<crate::model::ProbeInfo> {
    let _permit = probe_slots.acquire().await.expect("probe semaphore closed");
    ffmpeg::probe(&config.ffprobe_bin, request, config.probe_timeout).await
}

async fn terminate_child(
    child: &mut tokio::process::Child,
    child_stdin: &mut Option<ChildStdin>,
) -> Result<()> {
    // Интерактивная команда q позволяет FFmpeg закрыть muxer и сетевые сокеты.
    // Если процесс не отвечает, через три секунды выполняется принудительный kill.
    if let Some(mut stdin) = child_stdin.take() {
        let _ = stdin.write_all(b"q\n").await;
        drop(stdin);
    }
    match tokio::time::timeout(Duration::from_secs(3), child.wait()).await {
        Ok(result) => result.context("cannot reap FFmpeg process").map(|_| ()),
        Err(_) => {
            child.start_kill().context("cannot send kill signal")?;
            child.wait().await.context("cannot reap FFmpeg process")?;
            Ok(())
        }
    }
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

fn output_advanced(previous: Option<u64>, current: Option<u64>) -> bool {
    // Переход назад также означает движение: некоторые источники сбрасывают
    // timestamps после внутреннего discontinuity без перезапуска процесса.
    current.is_some() && current != previous
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

#[cfg(test)]
mod tests;
