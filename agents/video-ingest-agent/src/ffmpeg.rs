//! Адаптер к внешним программам FFmpeg и ffprobe.
//!
//! Агент не реализует видеокодеки самостоятельно. Rust безопасно формирует
//! массив аргументов и запускает готовые процессы без shell-интерпретации.

use std::{path::Path, process::Stdio, time::Duration};

use anyhow::{anyhow, Context, Result};
use serde::Deserialize;
use tokio::{process::Command, time::timeout};
use url::Url;

use crate::model::{Output, ProbeInfo, RtspTransport, StartPipelineRequest, VideoMode};

/// Проверяет наличие внешней программы до принятия pipeline-запросов.
pub async fn verify_binary(binary: &str) -> Result<()> {
    let output = timeout(
        Duration::from_secs(5),
        Command::new(binary)
            .arg("-version")
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .output(),
    )
    .await
    .with_context(|| format!("{binary} version check timed out"))?
    .with_context(|| format!("failed to execute {binary}"))?;
    if !output.status.success() {
        anyhow::bail!("{binary} version check failed");
    }
    Ok(())
}

// Эти приватные структуры повторяют только нужную часть JSON ffprobe.
// Serde проигнорирует все остальные поля ответа.
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

/// Запускает ffprobe и возвращает характеристики первого видеопотока.
pub async fn probe(
    ffprobe_bin: &str,
    request: &StartPipelineRequest,
    probe_timeout: Duration,
) -> Result<ProbeInfo> {
    probe_rtsp_url(
        ffprobe_bin,
        &request.rtsp_url,
        &request.transport,
        probe_timeout,
    )
    .await
}

/// Проверяет произвольный RTSP URL, включая опубликованный output MediaMTX.
pub async fn probe_rtsp_url(
    ffprobe_bin: &str,
    rtsp_url: &str,
    transport: &RtspTransport,
    probe_timeout: Duration,
) -> Result<ProbeInfo> {
    // timeout оборачивает Future запуска процесса. `kill_on_drop(true)` важен:
    // если timeout истечёт, незавершённый дочерний ffprobe будет уничтожен.
    let output = timeout(
        probe_timeout,
        Command::new(ffprobe_bin)
            .arg("-v")
            .arg("error")
            .arg("-rtsp_transport")
            .arg(transport.as_ffmpeg_value())
            .arg("-select_streams")
            .arg("v:0")
            .arg("-show_entries")
            .arg("stream=codec_name,width,height,avg_frame_rate")
            .arg("-of")
            .arg("json")
            .arg(rtsp_url)
            // piped позволяет родительскому процессу получить stdout/stderr.
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .output(),
    )
    .await
    // Здесь два Result подряд: внешний принадлежит timeout, внутренний —
    // запуску процесса. Поэтому используются два `?` с разными context.
    .context("ffprobe timed out")?
    .context("failed to execute ffprobe")?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(anyhow!(
            "ffprobe failed: {}",
            sanitize_message(&stderr, rtsp_url)
        ));
    }

    // Тип слева явно указывает Serde, в какую структуру читать JSON.
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

pub fn build_args(
    request: &StartPipelineRequest,
    data_dir: &Path,
    detected_codec: Option<&str>,
) -> Result<Vec<String>> {
    // URL проверяется до запуска процесса, чтобы вернуть HTTP 400 вместо
    // неясной диагностической ошибки FFmpeg.
    Url::parse(&request.rtsp_url).context("rtspUrl must be a valid URL")?;

    // Каждый элемент Vec<String> станет отдельным argv. Shell не используется,
    // поэтому символы `;`, `$()` и пробелы внутри URL не выполняются как команды.
    let mut args = vec![
        "-hide_banner".into(),
        "-nostdin".into(),
        "-loglevel".into(),
        "warning".into(),
        // Machine-readable progress идёт в stdout. Supervisor использует
        // первую запись progress=continue как подтверждение реального output.
        "-progress".into(),
        "pipe:1".into(),
        "-rtsp_transport".into(),
        request.transport.as_ffmpeg_value().into(),
        // RTSP demuxer называет socket I/O timeout просто `timeout`.
        // Значение задаётся в микросекундах: 5_000_000 = 5 секунд.
        "-timeout".into(),
        "5000000".into(),
        // Некоторые NVR передают корректную номинальную частоту кадров, но их
        // timestamps идут медленнее реального времени. Тогда двухсекундный HLS-
        // сегмент физически создаётся дольше двух секунд, и браузер регулярно
        // опустошает буфер. Wall-clock timestamps привязывают входные пакеты ко
        // времени их получения и сохраняют реальную скорость live-потока.
        "-use_wallclock_as_timestamps".into(),
        "1".into(),
        "-i".into(),
        request.rtsp_url.clone(),
        "-map".into(),
        "0:v:0".into(),
        "-an".into(),
    ];

    // `match` требует обработать все варианты enum. При добавлении нового режима
    // компилятор укажет все места, где логика ещё не реализована.
    match effective_video_mode(&request.video_mode, detected_codec) {
        // AUTO преобразован в конкретный режим функцией выше и сюда не попадёт.
        VideoMode::Auto => unreachable!("AUTO must be resolved before building FFmpeg args"),
        // COPY почти не использует CPU, но сохраняет исходный H.265, который
        // поддерживается не всеми браузерами.
        VideoMode::Copy => args.extend(["-c:v".into(), "copy".into()]),
        // H264 выполняет полное декодирование/кодирование и требует больше CPU.
        // H264 выполняет полное декодирование/кодирование и требует больше CPU.
        VideoMode::H264 => args.extend([
            // NVR может присылать кадры с неравномерными timestamps.
            // fps-filter восстанавливает ровный поток 15 FPS перед публикацией
            // в MediaMTX. Это сохраняет поведение прежнего CameraStreamWorker.
            "-vf".into(),
            "fps=15,format=yuv420p".into(),
            "-c:v".into(),
            "libx264".into(),
            "-preset".into(),
            "veryfast".into(),
            "-tune".into(),
            "zerolatency".into(),

            // Создаём ключевой кадр каждые 30 кадров.
            // При 15 FPS это примерно один ключевой кадр каждые 2 секунды.
            // MediaMTX сможет быстро начать формирование HLS после подключения UI.
            "-g".into(),
            "30".into(),
            "-keyint_min".into(),
            "30".into(),

            // Запрещаем FFmpeg произвольно менять интервал ключевых кадров.
            "-sc_threshold".into(),
            "0".into(),

            // B-frames увеличивают задержку и для live-потока не нужны.
            "-bf".into(),
            "0".into(),
        ]),
    }

    // Заимствуем output через `&`, чтобы не перемещать String из request.
    match &request.output {
        Output::Rtsp { url } => {
            Url::parse(url).context("RTSP output URL must be a valid URL")?;
            // Output transport is an output option here. TCP avoids RTP/UDP
            // connectivity problems between the agent and MediaMTX containers.
            args.extend([
                "-f".into(),
                "rtsp".into(),
                "-rtsp_transport".into(),
                "tcp".into(),
                url.clone(),
            ]);
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

/// Превращает AUTO в конкретное действие после ffprobe.
///
/// Названия `h264` и `avc1` означают уже совместимый с браузером AVC-поток.
/// Для HEVC и неизвестного кодека безопаснее выполнить преобразование в H.264.
pub fn effective_video_mode(mode: &VideoMode, detected_codec: Option<&str>) -> VideoMode {
    match mode {
        VideoMode::Auto => match detected_codec.map(|codec| codec.to_ascii_lowercase()) {
            Some(codec) if codec == "h264" || codec == "avc1" => VideoMode::Copy,
            _ => VideoMode::H264,
        },
        concrete => concrete.clone(),
    }
}

pub fn output_url(request: &StartPipelineRequest) -> Option<String> {
    // В текущей модели URL существует для обоих вариантов, но Option оставляет
    // возможность позднее добавить output без публичного URL, например S3.
    match &request.output {
        Output::Rtsp { url } => Some(url.clone()),
        Output::Hls { .. } => Some(format!("/hls/{}/index.m3u8", request.camera_id)),
    }
}

pub fn redact_url(value: &str) -> String {
    // Не режем строку вручную: Url корректно понимает userinfo и escaping.
    match Url::parse(value) {
        Ok(mut url) => {
            if !url.username().is_empty() {
                let _ = url.set_username("***");
            }
            if url.password().is_some() {
                let _ = url.set_password(Some("***"));
            }
            redact_xm_password(url.as_ref())
        }
        Err(_) => "<invalid-url>".into(),
    }
}

/// XM-NVR хранит credentials не в стандартном userinfo URL, а прямо в path:
/// `_password=secret_channel=8`. Поэтому обычный parser URL их не скрывает.
fn redact_xm_password(value: &str) -> String {
    let Some(marker_start) = value.find("_password=") else {
        return value.to_string();
    };
    let password_start = marker_start + "_password=".len();
    let password_tail = &value[password_start..];
    let password_end = password_tail
        .find("_channel=")
        .or_else(|| password_tail.find("_stream="))
        .map(|offset| password_start + offset)
        .unwrap_or(value.len());

    let mut redacted = value.to_string();
    redacted.replace_range(password_start..password_end, "***");
    redacted
}

pub fn sanitize_message(message: &str, source_url: &str) -> String {
    // FFmpeg иногда повторяет полный URL в stderr. Заменяем его целиком и
    // ограничиваем объём ошибки пятью строками.
    let redacted = redact_url(source_url);
    let sanitized = redact_xm_password(&message.replace(source_url, &redacted));
    sanitized
        .lines()
        .take(5)
        .collect::<Vec<_>>()
        .join(" | ")
}

fn parse_frame_rate(value: &str) -> Option<f64> {
    // ffprobe возвращает FPS дробью, например 30000/1001. Любая ошибка parsing
    // даёт None через `ok()?`, а не panic всего агента.
    let (numerator, denominator) = value.split_once('/')?;
    let numerator = numerator.parse::<f64>().ok()?;
    let denominator = denominator.parse::<f64>().ok()?;
    (denominator != 0.0).then_some(numerator / denominator)
}

#[cfg(test)]
mod tests {
    // Модуль tests компилируется только командой `cargo test`.
    // `super` означает родительский модуль ffmpeg.
    use std::path::Path;

    use uuid::Uuid;

    use crate::model::{Output, RtspTransport, StartPipelineRequest, VideoMode};

    use super::{
        build_args, effective_video_mode, parse_frame_rate, redact_url, sanitize_message,
    };

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
    fn xm_password_is_redacted() {
        let url = "rtsp://nvr.local/user=rt_password=secret_channel=8_stream=1.sdp?real_stream";
        let value = redact_url(url);
        assert!(!value.contains("secret"));
        assert!(value.contains("_password=***_channel=8"));

        let diagnostic = sanitize_message(
            "server rejected /user=rt_password=secret_channel=8_stream=1.sdp",
            url,
        );
        assert!(!diagnostic.contains("secret"));
    }

    #[test]
    fn auto_copies_h264_and_transcodes_hevc() {
        assert_eq!(
            effective_video_mode(&VideoMode::Auto, Some("h264")),
            VideoMode::Copy
        );
        assert_eq!(
            effective_video_mode(&VideoMode::Auto, Some("hevc")),
            VideoMode::H264
        );
        assert_eq!(
            effective_video_mode(&VideoMode::Auto, None),
            VideoMode::H264
        );
    }

    #[test]
    fn builds_hls_arguments_without_shell() {
        let args = build_args(&request(), Path::new("/data"), Some("h264")).unwrap();
        assert!(args.contains(&"-timeout".to_string()));
        assert!(!args.contains(&"-rw_timeout".to_string()));
        assert!(args.contains(&"copy".to_string()));
        assert!(args
            .last()
            .unwrap()
            .ends_with("/hls/00000000-0000-0000-0000-000000000000/index.m3u8"));
    }

    #[test]
    fn uses_wallclock_timestamps_for_rtsp_input() {
        let args = build_args(&request(), Path::new("/data"), Some("h264")).unwrap();
        let input_position = args.iter().position(|arg| arg == "-i").unwrap();

        assert_eq!(args[input_position - 2], "-use_wallclock_as_timestamps");
        assert_eq!(args[input_position - 1], "1");
    }

    #[test]
    fn h264_transcoding_normalizes_nvr_frame_rate() {
        let mut request = request();
        request.video_mode = VideoMode::H264;

        let args = build_args(&request, Path::new("/data"), Some("hevc")).unwrap();
        let filter_position = args.iter().position(|arg| arg == "-vf").unwrap();

        assert_eq!(args[filter_position + 1], "fps=15,format=yuv420p");
    }

    #[test]
    fn publishes_rtsp_to_mediamtx_over_tcp() {
        let mut request = request();
        request.output = Output::Rtsp {
            url: "rtsp://mediamtx:8554/camera".into(),
        };

        let args = build_args(&request, Path::new("/data"), Some("h264")).unwrap();
        let output_url_position = args
            .iter()
            .position(|arg| arg == "rtsp://mediamtx:8554/camera")
            .unwrap();

        assert_eq!(args[output_url_position - 2], "-rtsp_transport");
        assert_eq!(args[output_url_position - 1], "tcp");
    }

    #[test]
    fn parses_fractional_frame_rate() {
        assert_eq!(parse_frame_rate("30000/1001").unwrap().round(), 30.0);
    }
}
