//! Метрики агента в текстовом формате Prometheus.

use anyhow::Result;
use prometheus::{Encoder, IntCounter, IntGauge, Registry, TextEncoder};

/// Набор metric handles.
///
/// Клонирование handle не создаёт новую метрику: копии указывают на один и тот
/// же внутренний atomic-счётчик, поэтому их можно передавать в async-задачи.
#[derive(Clone)]
pub struct Metrics {
    registry: Registry,
    pub active_pipelines: IntGauge,
    pub process_starts: IntCounter,
    pub process_failures: IntCounter,
    pub reconnects: IntCounter,
}

impl Metrics {
    pub fn new() -> Result<Self> {
        // Отдельный Registry не загрязняет глобальный реестр процесса и делает
        // набор экспортируемых метрик полностью явным.
        let registry = Registry::new();
        let active_pipelines = IntGauge::new(
            "video_ingest_active_pipelines",
            "Number of currently registered video pipelines",
        )?;
        let process_starts = IntCounter::new(
            "video_ingest_process_starts_total",
            "Number of FFmpeg process starts",
        )?;
        let process_failures = IntCounter::new(
            "video_ingest_process_failures_total",
            "Number of unsuccessful FFmpeg exits or spawn failures",
        )?;
        let reconnects = IntCounter::new(
            "video_ingest_reconnects_total",
            "Number of FFmpeg reconnect attempts",
        )?;

        // Registry хранит trait object в heap (`Box`). Handle оставляем в
        // структуре, чтобы затем вызывать inc/dec из менеджера.
        registry.register(Box::new(active_pipelines.clone()))?;
        registry.register(Box::new(process_starts.clone()))?;
        registry.register(Box::new(process_failures.clone()))?;
        registry.register(Box::new(reconnects.clone()))?;

        Ok(Self {
            registry,
            active_pipelines,
            process_starts,
            process_failures,
            reconnects,
        })
    }

    /// Собирает текущие значения и кодирует их для ответа `GET /metrics`.
    pub fn encode(&self) -> Result<String> {
        let families = self.registry.gather();
        let mut buffer = Vec::new();
        TextEncoder::new().encode(&families, &mut buffer)?;
        Ok(String::from_utf8(buffer)?)
    }
}
