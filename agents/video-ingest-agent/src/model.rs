//! DTO и состояния видеопайплайна.
//!
//! `serde` преобразует эти Rust-типы в JSON и обратно. Атрибуты `#[serde(...)]`
//! задают внешний JSON-контракт, не меняя принятый в Rust snake_case.

use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Транспорт нижнего уровня для получения RTSP-пакетов.
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RtspTransport {
    Tcp,
    Udp,
}

impl Default for RtspTransport {
    /// TCP устойчивее к потерям пакетов и поэтому выбран по умолчанию.
    fn default() -> Self {
        Self::Tcp
    }
}

impl RtspTransport {
    /// Преобразует enum в точное строковое значение, ожидаемое FFmpeg.
    /// `&'static str` означает ссылку на строку, живущую всё время программы.
    pub fn as_ffmpeg_value(&self) -> &'static str {
        match self {
            Self::Tcp => "tcp",
            Self::Udp => "udp",
        }
    }
}

/// Куда FFmpeg должен направлять обработанное видео.
///
/// `tag = "type"` создаёт tagged union в JSON, например
/// `{ "type": "RTSP", "url": "rtsp://..." }`.
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Output {
    /// Публикация в MediaMTX или другой RTSP-сервер.
    Rtsp { url: String },
    /// Скользящий live HLS в `AGENT_DATA_DIR/hls/{cameraId}`.
    Hls {
        #[serde(default = "default_segment_seconds")]
        segment_seconds: u16,
        #[serde(default = "default_playlist_segments")]
        playlist_segments: u16,
    },
}

fn default_segment_seconds() -> u16 {
    2
}

fn default_playlist_segments() -> u16 {
    6
}

/// Режим обработки видеокодека.
#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum VideoMode {
    /// Не декодировать кадры: минимальная нагрузка на CPU, исходный кодек
    /// сохраняется. H.265 при этом может не воспроизводиться браузером.
    #[default]
    Copy,
    /// Декодировать вход и кодировать результат в browser-friendly H.264.
    H264,
}

/// Тело команды запуска, принимаемое `POST /v1/pipelines`.
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct StartPipelineRequest {
    // Если commandId не передан, serde вызовет функцию и создаст UUID.
    #[serde(default = "Uuid::new_v4")]
    pub command_id: Uuid,
    pub camera_id: Uuid,
    pub rtsp_url: String,
    #[serde(default)]
    pub transport: RtspTransport,
    #[serde(default)]
    pub video_mode: VideoMode,
    pub output: Output,
    #[serde(default = "default_reconnect")]
    pub reconnect: bool,
}

fn default_reconnect() -> bool {
    true
}

/// Технические параметры входного видеопотока, полученные через ffprobe.
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProbeInfo {
    pub codec: String,
    pub width: u32,
    pub height: u32,
    pub fps: Option<f64>,
}

/// Конечный автомат одного пайплайна.
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PipelineState {
    Starting,
    Running,
    Reconnecting,
    Stopping,
    Stopped,
    Failed,
}

/// Наблюдаемое состояние, возвращаемое HTTP API.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PipelineStatus {
    pub agent_id: String,
    pub command_id: Uuid,
    pub camera_id: Uuid,
    pub state: PipelineState,
    /// PID существует только пока дочерний FFmpeg запущен.
    pub pid: Option<u32>,
    pub restart_count: u32,
    pub probe: Option<ProbeInfo>,
    pub output_url: Option<String>,
    pub last_error: Option<String>,
    pub updated_at_epoch_ms: u128,
}

impl PipelineStatus {
    /// Конструктор начального состояния. `&StartPipelineRequest` — заимствование:
    /// функция читает request, но не получает его во владение и не уничтожает.
    pub fn starting(agent_id: String, request: &StartPipelineRequest) -> Self {
        Self {
            agent_id,
            command_id: request.command_id,
            camera_id: request.camera_id,
            state: PipelineState::Starting,
            pid: None,
            restart_count: 0,
            probe: None,
            output_url: None,
            last_error: None,
            updated_at_epoch_ms: now_epoch_ms(),
        }
    }

    /// Обновляет отметку времени после каждого изменения состояния.
    /// `&mut self` требует эксклюзивный изменяемый доступ к объекту.
    pub fn touch(&mut self) {
        self.updated_at_epoch_ms = now_epoch_ms();
    }
}

fn now_epoch_ms() -> u128 {
    // При невозможной на современных системах дате до Unix epoch используем 0,
    // чтобы служебная метка времени не приводила к panic всего агента.
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}
