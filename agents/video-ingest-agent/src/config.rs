//! Чтение конфигурации агента из переменных окружения.
//!
//! В отличие от Spring Boot здесь нет автоматического binding в объект:
//! каждую переменную читаем и преобразуем явно.

use std::{env, net::SocketAddr, path::PathBuf, time::Duration};

use anyhow::{Context, Result};

/// Неизменяемая после старта конфигурация процесса.
///
/// `Clone` нужен, чтобы безопасно передавать копию конфигурации в async-задачи.
/// `Debug` удобен при диагностике, но весь объект не следует логировать:
/// `api_token` является секретом.
#[derive(Clone, Debug)]
pub struct Config {
    /// Адрес HTTP-сервера в формате `IP:port`.
    pub bind: SocketAddr,
    /// Стабильное имя данного media-node.
    pub agent_id: String,
    /// `None` разрешает запросы без токена — допустимо только локально.
    pub api_token: Option<String>,
    /// Корень для HLS-плейлистов и сегментов.
    pub data_dir: PathBuf,
    /// Имя или полный путь к исполняемому файлу FFmpeg.
    pub ffmpeg_bin: String,
    /// Имя или полный путь к ffprobe.
    pub ffprobe_bin: String,
    /// Максимальное время предварительной проверки RTSP.
    pub probe_timeout: Duration,
    /// Максимальное ожидание первого подтверждения обработки кадров FFmpeg.
    pub ready_timeout: Duration,
    /// Максимальное ожидание доступности опубликованного RTSP-output.
    pub output_ready_timeout: Duration,
    /// Максимальное время без продвижения output timestamp после запуска.
    pub output_stall_timeout: Duration,
}

impl Config {
    pub fn from_env() -> Result<Self> {
        // `unwrap_or_else` вычисляет значение по умолчанию только тогда,
        // когда переменная отсутствует. `.into()` преобразует &str в String.
        let bind = env::var("AGENT_BIND")
            .unwrap_or_else(|_| "127.0.0.1:8098".into())
            // Rust выводит требуемый тип `SocketAddr` из поля `bind` ниже.
            .parse()
            // `context` добавляет понятное описание к исходной ошибке.
            .context("AGENT_BIND must be a valid socket address")?;

        let probe_timeout_seconds = env::var("AGENT_PROBE_TIMEOUT_SECONDS")
            .unwrap_or_else(|_| "15".into())
            .parse::<u64>()
            .context("AGENT_PROBE_TIMEOUT_SECONDS must be an integer")?;

        let ready_timeout_seconds = env::var("AGENT_READY_TIMEOUT_SECONDS")
            .unwrap_or_else(|_| "30".into())
            .parse::<u64>()
            .context("AGENT_READY_TIMEOUT_SECONDS must be an integer")?;

        let output_stall_timeout_seconds = env::var("AGENT_OUTPUT_STALL_TIMEOUT_SECONDS")
            .unwrap_or_else(|_| "15".into())
            .parse::<u64>()
            .context("AGENT_OUTPUT_STALL_TIMEOUT_SECONDS must be an integer")?;

        let output_ready_timeout_seconds = env::var("AGENT_OUTPUT_READY_TIMEOUT_SECONDS")
            .unwrap_or_else(|_| "15".into())
            .parse::<u64>()
            .context("AGENT_OUTPUT_READY_TIMEOUT_SECONDS must be an integer")?;

        if output_ready_timeout_seconds == 0 {
            anyhow::bail!("AGENT_OUTPUT_READY_TIMEOUT_SECONDS must be positive");
        }
        if output_stall_timeout_seconds == 0 {
            anyhow::bail!("AGENT_OUTPUT_STALL_TIMEOUT_SECONDS must be positive");
        }

        Ok(Self {
            bind,
            agent_id: env::var("AGENT_ID").unwrap_or_else(|_| "media-node-01".into()),
            // `.ok()` превращает Result<String, _> в Option<String>:
            // отсутствующая переменная становится None, а не ошибкой старта.
            api_token: env::var("AGENT_API_TOKEN").ok().filter(|v| !v.is_empty()),
            data_dir: env::var("AGENT_DATA_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(|_| PathBuf::from("./data")),
            ffmpeg_bin: env::var("FFMPEG_BIN").unwrap_or_else(|_| "ffmpeg".into()),
            ffprobe_bin: env::var("FFPROBE_BIN").unwrap_or_else(|_| "ffprobe".into()),
            probe_timeout: Duration::from_secs(probe_timeout_seconds),
            ready_timeout: Duration::from_secs(ready_timeout_seconds),
            output_ready_timeout: Duration::from_secs(output_ready_timeout_seconds),
            output_stall_timeout: Duration::from_secs(output_stall_timeout_seconds),
        })
    }
}
