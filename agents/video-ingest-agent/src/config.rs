use std::{env, net::SocketAddr, path::PathBuf, time::Duration};

use anyhow::{Context, Result};

#[derive(Clone, Debug)]
pub struct Config {
    pub bind: SocketAddr,
    pub agent_id: String,
    pub api_token: Option<String>,
    pub data_dir: PathBuf,
    pub ffmpeg_bin: String,
    pub ffprobe_bin: String,
    pub probe_timeout: Duration,
}

impl Config {
    pub fn from_env() -> Result<Self> {
        let bind = env::var("AGENT_BIND")
            .unwrap_or_else(|_| "127.0.0.1:8098".into())
            .parse()
            .context("AGENT_BIND must be a valid socket address")?;

        let probe_timeout_seconds = env::var("AGENT_PROBE_TIMEOUT_SECONDS")
            .unwrap_or_else(|_| "15".into())
            .parse::<u64>()
            .context("AGENT_PROBE_TIMEOUT_SECONDS must be an integer")?;

        Ok(Self {
            bind,
            agent_id: env::var("AGENT_ID").unwrap_or_else(|_| "media-node-01".into()),
            api_token: env::var("AGENT_API_TOKEN").ok().filter(|v| !v.is_empty()),
            data_dir: env::var("AGENT_DATA_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(|_| PathBuf::from("./data")),
            ffmpeg_bin: env::var("FFMPEG_BIN").unwrap_or_else(|_| "ffmpeg".into()),
            ffprobe_bin: env::var("FFPROBE_BIN").unwrap_or_else(|_| "ffprobe".into()),
            probe_timeout: Duration::from_secs(probe_timeout_seconds),
        })
    }
}
