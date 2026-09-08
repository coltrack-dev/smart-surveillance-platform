use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RtspTransport {
    Tcp,
    Udp,
}

impl Default for RtspTransport {
    fn default() -> Self {
        Self::Tcp
    }
}

impl RtspTransport {
    pub fn as_ffmpeg_value(&self) -> &'static str {
        match self {
            Self::Tcp => "tcp",
            Self::Udp => "udp",
        }
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Output {
    /// Publish the source to MediaMTX or another RTSP server.
    Rtsp { url: String },
    /// Create a rolling live HLS playlist below AGENT_DATA_DIR/hls/{cameraId}.
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

#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum VideoMode {
    #[default]
    Copy,
    H264,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct StartPipelineRequest {
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

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProbeInfo {
    pub codec: String,
    pub width: u32,
    pub height: u32,
    pub fps: Option<f64>,
}

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

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PipelineStatus {
    pub agent_id: String,
    pub command_id: Uuid,
    pub camera_id: Uuid,
    pub state: PipelineState,
    pub pid: Option<u32>,
    pub restart_count: u32,
    pub probe: Option<ProbeInfo>,
    pub output_url: Option<String>,
    pub last_error: Option<String>,
    pub updated_at_epoch_ms: u128,
}

impl PipelineStatus {
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

    pub fn touch(&mut self) {
        self.updated_at_epoch_ms = now_epoch_ms();
    }
}

fn now_epoch_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}
