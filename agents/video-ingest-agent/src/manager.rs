use std::{collections::HashMap, process::Stdio, sync::Arc, time::Duration};

use anyhow::{Context, Result};
use tokio::{
    fs,
    io::{AsyncBufReadExt, BufReader},
    process::Command,
    sync::{watch, Mutex, RwLock},
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

#[derive(Debug, thiserror::Error)]
pub enum ManagerError {
    #[error("pipeline for camera {0} already exists")]
    AlreadyExists(Uuid),
    #[error("pipeline for camera {0} was not found")]
    NotFound(Uuid),
    #[error("invalid pipeline: {0}")]
    Invalid(String),
}

struct PipelineControl {
    status: Arc<RwLock<PipelineStatus>>,
    stop_tx: watch::Sender<bool>,
    task: Mutex<Option<JoinHandle<()>>>,
    cleanup_dir: Option<std::path::PathBuf>,
}

#[derive(Clone)]
pub struct PipelineManager {
    config: Config,
    metrics: Metrics,
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
        validate_request(&request).map_err(|e| ManagerError::Invalid(e.to_string()))?;

        let mut pipelines = self.pipelines.write().await;
        if let Some(existing) = pipelines.get(&request.camera_id) {
            let status = existing.status.read().await.clone();
            if status.command_id == request.command_id {
                return Ok(status);
            }
            return Err(ManagerError::AlreadyExists(request.camera_id));
        }

        let cleanup_dir = if let Output::Hls { .. } = &request.output {
            let directory = self
                .config
                .data_dir
                .join("hls")
                .join(request.camera_id.to_string());
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

        let status = Arc::new(RwLock::new(PipelineStatus::starting(
            self.config.agent_id.clone(),
            &request,
        )));
        let initial_status = status.read().await.clone();
        let (stop_tx, stop_rx) = watch::channel(false);
        let control = Arc::new(PipelineControl {
            status: status.clone(),
            stop_tx,
            task: Mutex::new(None),
            cleanup_dir,
        });
        pipelines.insert(request.camera_id, control.clone());
        self.metrics.active_pipelines.inc();
        drop(pipelines);

        let config = self.config.clone();
        let metrics = self.metrics.clone();
        let task = tokio::spawn(async move {
            supervise(config, metrics, request, status, stop_rx).await;
        });
        *control.task.lock().await = Some(task);

        Ok(initial_status)
    }

    pub async fn stop(&self, camera_id: Uuid) -> Result<PipelineStatus, ManagerError> {
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
        let _ = control.stop_tx.send(true);
        if let Some(task) = control.task.lock().await.take() {
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
        let controls: Vec<_> = self.pipelines.read().await.values().cloned().collect();
        let mut result = Vec::with_capacity(controls.len());
        for control in controls {
            result.push(control.status.read().await.clone());
        }
        result.sort_by_key(|item| item.camera_id);
        result
    }

    pub async fn shutdown(&self) {
        let camera_ids: Vec<_> = self.pipelines.read().await.keys().copied().collect();
        for camera_id in camera_ids {
            let _ = self.stop(camera_id).await;
        }
    }
}

async fn supervise(
    config: Config,
    metrics: Metrics,
    request: StartPipelineRequest,
    status: Arc<RwLock<PipelineStatus>>,
    mut stop_rx: watch::Receiver<bool>,
) {
    match ffmpeg::probe(&config.ffprobe_bin, &request, config.probe_timeout).await {
        Ok(probe) => status.write().await.probe = Some(probe),
        Err(error) => {
            let message = error.to_string();
            warn!(camera_id = %request.camera_id, error = %message, "RTSP probe failed");
            let mut current = status.write().await;
            current.last_error = Some(message);
            current.touch();
        }
    }

    let args = match ffmpeg::build_args(&request, &config.data_dir) {
        Ok(args) => args,
        Err(error) => {
            fail_status(&status, error.to_string()).await;
            return;
        }
    };

    let mut backoff = Duration::from_secs(1);
    loop {
        if *stop_rx.borrow() {
            stopped_status(&status).await;
            return;
        }

        info!(
            camera_id = %request.camera_id,
            source = %ffmpeg::redact_url(&request.rtsp_url),
            "starting FFmpeg pipeline"
        );

        let mut child = match Command::new(&config.ffmpeg_bin)
            .args(&args)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
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
                continue;
            }
        };

        metrics.process_starts.inc();
        let pid = child.id();
        {
            let mut current = status.write().await;
            current.state = PipelineState::Running;
            current.pid = pid;
            current.output_url = ffmpeg::output_url(&request);
            current.last_error = None;
            current.touch();
        }

        let stderr_task = child.stderr.take().map(|stderr| {
            let camera_id = request.camera_id;
            let source_url = request.rtsp_url.clone();
            tokio::spawn(async move {
                let mut lines = BufReader::new(stderr).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    let safe_line = ffmpeg::sanitize_message(&line, &source_url);
                    warn!(camera_id = %camera_id, ffmpeg = %safe_line, "FFmpeg diagnostic");
                }
            })
        });

        let exit_result = tokio::select! {
            changed = stop_rx.changed() => {
                if changed.is_err() || *stop_rx.borrow() {
                    if let Err(error) = terminate_child(&mut child).await {
                        warn!(camera_id = %request.camera_id, error = %error, "failed to terminate FFmpeg cleanly");
                    }
                    None
                } else {
                    child.wait().await.ok()
                }
            }
            result = child.wait() => result.ok(),
        };

        if let Some(task) = stderr_task {
            task.abort();
        }

        if *stop_rx.borrow() {
            stopped_status(&status).await;
            return;
        }

        metrics.process_failures.inc();
        let message = match exit_result {
            Some(exit) => format!("FFmpeg exited with {exit}"),
            None => "FFmpeg wait failed".into(),
        };
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
    child.start_kill().context("cannot send kill signal")?;
    child.wait().await.context("cannot reap FFmpeg process")?;
    Ok(())
}

async fn wait_or_stop(stop_rx: &mut watch::Receiver<bool>, duration: Duration) -> bool {
    tokio::select! {
        _ = sleep(duration) => false,
        changed = stop_rx.changed() => changed.is_err() || *stop_rx.borrow(),
    }
}

fn next_backoff(current: Duration) -> Duration {
    current.saturating_mul(2).min(Duration::from_secs(30))
}

async fn reconnect_status(status: &RwLock<PipelineStatus>, error: String) {
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
