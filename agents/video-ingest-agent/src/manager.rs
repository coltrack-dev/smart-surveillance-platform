//! Жизненный цикл видеопайплайнов и дочерних FFmpeg-процессов.
//!
//! Центральный модуль агента. `PipelineManager` хранит реестр камер, а для
//! каждой камеры отдельная Tokio task выполняет `supervise`: запускает FFmpeg,
//! ждёт его завершения и при необходимости делает reconnect.

use std::{
    collections::HashMap,
    sync::Arc,
};

use anyhow::{Context, Result};
use tokio::{
    fs,
    sync::{watch, Mutex, RwLock, Semaphore},
    task::JoinHandle,
};
use tracing::warn;
use uuid::Uuid;

use crate::{
    config::Config,
    metrics::Metrics,
    model::{Output, PipelineState, PipelineStatus, StartPipelineRequest},
    supervisor,
};

/// Ошибки, которые HTTP-слой может однозначно преобразовать в status code.
#[derive(Debug, thiserror::Error)]
pub enum ManagerError {
    #[error("pipeline for camera {0} already exists")]
    AlreadyExists(Uuid),
    #[error("pipeline for camera {0} was not found")]
    NotFound(Uuid),
    #[error("pipeline capacity {0} is exhausted")]
    CapacityExceeded(usize),
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

/// Потокобезопасный реестр всех активных пайплайнов данного агента.
#[derive(Clone)]
pub struct PipelineManager {
    config: Config,
    metrics: Metrics,
    // Аналог ConcurrentHashMap<UUID, PipelineControl> из Java, но доступ явно
    // разделён на read().await и write().await.
    pipelines: Arc<RwLock<HashMap<Uuid, Arc<PipelineControl>>>>,
    /// Один semaphore ограничивает суммарное число probe всех камер.
    probe_slots: Arc<Semaphore>,
}

impl PipelineManager {
    pub fn new(config: Config, metrics: Metrics) -> Self {
        let probe_slots = Arc::new(Semaphore::new(config.max_concurrent_probes));
        Self {
            config,
            metrics,
            pipelines: Arc::new(RwLock::new(HashMap::new())),
            probe_slots,
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
        if pipelines.len() >= self.config.max_pipelines {
            return Err(ManagerError::CapacityExceeded(self.config.max_pipelines));
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
        let probe_slots = self.probe_slots.clone();
        // `async move` передаёт владение config/request/status/stop_rx фоновой
        // задаче. Без move ссылки могли бы пережить stack frame метода start.
        let task = tokio::spawn(async move {
            supervisor::supervise(config, metrics, request, status, stop_rx, probe_slots).await;
        });
        *control.task.lock().await = Some(task);

        Ok(initial_status)
    }

    pub async fn stop(&self, camera_id: Uuid) -> Result<PipelineStatus, ManagerError> {
        // Оставляем control в map на всё время остановки. Это резервирует cameraId:
        // параллельный start увидит STOPPING/AlreadyExists и не сможет запустить
        // второй FFmpeg до того, как первый процесс будет полностью reaped.
        let control = self
            .pipelines
            .read()
            .await
            .get(&camera_id)
            .cloned()
            .ok_or(ManagerError::NotFound(camera_id))?;

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

        // Удаляем только тот control, который останавливали. Проверка Arc
        // защищает от удаления новой записи, если реализация start изменится.
        let removed = {
            let mut pipelines = self.pipelines.write().await;
            let is_same = pipelines
                .get(&camera_id)
                .is_some_and(|current| Arc::ptr_eq(current, &control));
            if is_same {
                pipelines.remove(&camera_id);
                true
            } else {
                false
            }
        };
        if removed {
            self.metrics.active_pipelines.dec();
        }
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
