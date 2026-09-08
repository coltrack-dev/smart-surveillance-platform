use std::{path::Path, process::Stdio, time::Duration};

use anyhow::{anyhow, Context, Result};
use serde::Deserialize;
use tokio::{process::Command, time::timeout};
use url::Url;

use crate::model::{Output, ProbeInfo, StartPipelineRequest, VideoMode};

#[derive(Debug, Deserialize)]
struct ProbeDocument {
    streams: Vec<ProbeStream>,
}

#[derive(Debug, Deserialize)]
struct ProbeStream {
    codec_name: Option<String>,
    width: Option<u32>,
    height: Option<u32>,
    avg_frame_rate: Option<String>,
}

pub async fn probe(
    ffprobe_bin: &str,
    request: &StartPipelineRequest,
    probe_timeout: Duration,
) -> Result<ProbeInfo> {
    let output = timeout(
        probe_timeout,
        Command::new(ffprobe_bin)
            .arg("-v")
            .arg("error")
            .arg("-rtsp_transport")
            .arg(request.transport.as_ffmpeg_value())
            .arg("-select_streams")
            .arg("v:0")
            .arg("-show_entries")
            .arg("stream=codec_name,width,height,avg_frame_rate")
            .arg("-of")
            .arg("json")
            .arg(&request.rtsp_url)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .output(),
    )
    .await
    .context("ffprobe timed out")?
    .context("failed to execute ffprobe")?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(anyhow!(
            "ffprobe failed: {}",
            sanitize_message(&stderr, &request.rtsp_url)
        ));
    }

    let document: ProbeDocument =
        serde_json::from_slice(&output.stdout).context("invalid ffprobe JSON")?;
    let stream = document
        .streams
        .into_iter()
        .next()
        .context("ffprobe found no video stream")?;

    Ok(ProbeInfo {
        codec: stream.codec_name.unwrap_or_else(|| "unknown".into()),
        width: stream.width.unwrap_or_default(),
        height: stream.height.unwrap_or_default(),
        fps: stream.avg_frame_rate.as_deref().and_then(parse_frame_rate),
    })
}

pub fn build_args(request: &StartPipelineRequest, data_dir: &Path) -> Result<Vec<String>> {
    Url::parse(&request.rtsp_url).context("rtspUrl must be a valid URL")?;

    let mut args = vec![
        "-hide_banner".into(),
        "-nostdin".into(),
        "-loglevel".into(),
        "warning".into(),
        "-rtsp_transport".into(),
        request.transport.as_ffmpeg_value().into(),
        "-rw_timeout".into(),
        "5000000".into(),
        "-i".into(),
        request.rtsp_url.clone(),
        "-map".into(),
        "0:v:0".into(),
        "-an".into(),
    ];

    match &request.video_mode {
        VideoMode::Copy => args.extend(["-c:v".into(), "copy".into()]),
        VideoMode::H264 => args.extend([
            "-c:v".into(),
            "libx264".into(),
            "-preset".into(),
            "veryfast".into(),
            "-tune".into(),
            "zerolatency".into(),
            "-pix_fmt".into(),
            "yuv420p".into(),
        ]),
    }

    match &request.output {
        Output::Rtsp { url } => {
            Url::parse(url).context("RTSP output URL must be a valid URL")?;
            args.extend(["-f".into(), "rtsp".into(), url.clone()]);
        }
        Output::Hls {
            segment_seconds,
            playlist_segments,
        } => {
            if *segment_seconds == 0 || *playlist_segments < 2 {
                return Err(anyhow!(
                    "HLS segmentSeconds must be positive and playlistSegments must be at least 2"
                ));
            }
            let playlist = data_dir
                .join("hls")
                .join(request.camera_id.to_string())
                .join("index.m3u8");
            args.extend([
                "-f".into(),
                "hls".into(),
                "-hls_time".into(),
                segment_seconds.to_string(),
                "-hls_list_size".into(),
                playlist_segments.to_string(),
                "-hls_flags".into(),
                "delete_segments+append_list+independent_segments".into(),
                playlist.to_string_lossy().into_owned(),
            ]);
        }
    }

    Ok(args)
}

pub fn output_url(request: &StartPipelineRequest) -> Option<String> {
    match &request.output {
        Output::Rtsp { url } => Some(url.clone()),
        Output::Hls { .. } => Some(format!("/hls/{}/index.m3u8", request.camera_id)),
    }
}

pub fn redact_url(value: &str) -> String {
    match Url::parse(value) {
        Ok(mut url) => {
            if !url.username().is_empty() {
                let _ = url.set_username("***");
            }
            if url.password().is_some() {
                let _ = url.set_password(Some("***"));
            }
            url.to_string()
        }
        Err(_) => "<invalid-url>".into(),
    }
}

pub fn sanitize_message(message: &str, source_url: &str) -> String {
    let redacted = redact_url(source_url);
    message
        .replace(source_url, &redacted)
        .lines()
        .take(5)
        .collect::<Vec<_>>()
        .join(" | ")
}

fn parse_frame_rate(value: &str) -> Option<f64> {
    let (numerator, denominator) = value.split_once('/')?;
    let numerator = numerator.parse::<f64>().ok()?;
    let denominator = denominator.parse::<f64>().ok()?;
    (denominator != 0.0).then_some(numerator / denominator)
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use uuid::Uuid;

    use crate::model::{Output, RtspTransport, StartPipelineRequest, VideoMode};

    use super::{build_args, parse_frame_rate, redact_url, sanitize_message};

    fn request() -> StartPipelineRequest {
        StartPipelineRequest {
            command_id: Uuid::new_v4(),
            camera_id: Uuid::nil(),
            rtsp_url: "rtsp://admin:secret@nvr.local/live".into(),
            transport: RtspTransport::Tcp,
            video_mode: VideoMode::Copy,
            output: Output::Hls {
                segment_seconds: 2,
                playlist_segments: 6,
            },
            reconnect: true,
        }
    }

    #[test]
    fn credentials_are_redacted() {
        let value = redact_url("rtsp://admin:secret@nvr.local/live");
        assert!(!value.contains("admin"));
        assert!(!value.contains("secret"));
    }

    #[test]
    fn credentials_are_redacted_from_diagnostics() {
        let url = "rtsp://admin:secret@nvr.local/live";
        let value = sanitize_message(&format!("cannot open {url}"), url);
        assert!(!value.contains("admin"));
        assert!(!value.contains("secret"));
    }

    #[test]
    fn builds_hls_arguments_without_shell() {
        let args = build_args(&request(), Path::new("/data")).unwrap();
        assert!(args.contains(&"copy".to_string()));
        assert!(args.last().unwrap().ends_with("/hls/00000000-0000-0000-0000-000000000000/index.m3u8"));
    }

    #[test]
    fn parses_fractional_frame_rate() {
        assert_eq!(parse_frame_rate("30000/1001").unwrap().round(), 30.0);
    }
}
