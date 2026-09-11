#[cfg(unix)]
use std::{fs::Permissions, os::unix::fs::PermissionsExt, path::Path};

use tokio::time::{sleep, Duration};

use crate::{
    config::Config,
    manager::{ManagerError, PipelineManager},
    metrics::Metrics,
    model::{Output, PipelineState, RtspTransport, StartPipelineRequest, VideoMode},
};

use super::{output_advanced, ProgressParser};

#[test]
fn parses_ffmpeg_progress_output_time() {
    let mut parser = ProgressParser::default();

    assert!(parser.accept("out_time_us=1250000").is_none());
    let progress = parser.accept("progress=continue").unwrap();

    assert_eq!(progress.output_time_ms, Some(1_250));
}

#[test]
fn detects_only_media_time_changes_as_progress() {
    assert!(!output_advanced(Some(1_000), Some(1_000)));
    assert!(output_advanced(Some(1_000), Some(1_001)));
    assert!(output_advanced(Some(1_000), Some(10)));
    assert!(!output_advanced(None, None));
}

#[tokio::test]
async fn rejects_pipeline_above_configured_capacity() {
    let manager = PipelineManager::new(
        Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            agent_id: "test-agent".into(),
            api_token: None,
            data_dir: std::env::temp_dir(),
            ffmpeg_bin: "missing-test-ffmpeg".into(),
            ffprobe_bin: "missing-test-ffprobe".into(),
            probe_timeout: Duration::from_millis(10),
            ready_timeout: Duration::from_millis(10),
            output_ready_timeout: Duration::from_millis(10),
            output_health_interval: Duration::from_secs(1),
            output_stall_timeout: Duration::from_secs(1),
            max_pipelines: 1,
            max_concurrent_probes: 1,
        },
        Metrics::new().unwrap(),
    );
    let request = |camera_id| StartPipelineRequest {
        command_id: uuid::Uuid::new_v4(),
        camera_id,
        rtsp_url: "rtsp://camera.test/live".into(),
        transport: RtspTransport::Tcp,
        video_mode: VideoMode::Copy,
        output: Output::Rtsp {
            url: format!("rtsp://mediamtx.test:8554/{camera_id}"),
        },
        reconnect: true,
    };

    manager.start(request(uuid::Uuid::new_v4())).await.unwrap();
    let error = manager
        .start(request(uuid::Uuid::new_v4()))
        .await
        .unwrap_err();

    assert!(matches!(error, ManagerError::CapacityExceeded(1)));
    manager.shutdown().await;
}

#[cfg(unix)]
#[tokio::test]
async fn reconnects_when_fake_ffmpeg_stops_advancing_output() {
    let test_dir = std::env::temp_dir().join(format!(
        "video-ingest-agent-watchdog-{}",
        uuid::Uuid::new_v4()
    ));
    tokio::fs::create_dir_all(&test_dir).await.unwrap();
    let ffprobe = test_dir.join("fake-ffprobe.sh");
    let ffmpeg = test_dir.join("fake-ffmpeg.sh");

    write_executable(
        &ffprobe,
        "#!/bin/sh\nprintf '%s\\n' '{\"streams\":[{\"codec_name\":\"h264\",\"width\":640,\"height\":360,\"avg_frame_rate\":\"15/1\"}]}'\n",
    )
    .await;
    write_executable(
        &ffmpeg,
        "#!/bin/sh\nprintf 'out_time_us=1000000\\nprogress=continue\\n'\nread _\n",
    )
    .await;

    let metrics = Metrics::new().unwrap();
    let manager = PipelineManager::new(
        Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            agent_id: "test-agent".into(),
            api_token: None,
            data_dir: test_dir.clone(),
            ffmpeg_bin: ffmpeg.to_string_lossy().into_owned(),
            ffprobe_bin: ffprobe.to_string_lossy().into_owned(),
            probe_timeout: Duration::from_secs(1),
            ready_timeout: Duration::from_secs(1),
            output_ready_timeout: Duration::from_secs(1),
            output_health_interval: Duration::from_secs(1),
            output_stall_timeout: Duration::from_millis(100),
            max_pipelines: 8,
            max_concurrent_probes: 4,
        },
        metrics.clone(),
    );
    let camera_id = uuid::Uuid::new_v4();
    manager
        .start(StartPipelineRequest {
            command_id: uuid::Uuid::new_v4(),
            camera_id,
            rtsp_url: "rtsp://camera.test/live".into(),
            transport: RtspTransport::Tcp,
            video_mode: VideoMode::Copy,
            output: Output::Rtsp {
                url: "rtsp://mediamtx.test:8554/camera".into(),
            },
            reconnect: true,
        })
        .await
        .unwrap();

    let status = tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            let status = manager.get(camera_id).await.unwrap();
            if status.state == PipelineState::Reconnecting {
                break status;
            }
            sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("pipeline did not enter RECONNECTING after output stalled");
    assert_eq!(status.state, PipelineState::Reconnecting);
    assert_eq!(status.restart_count, 1);
    assert_eq!(metrics.output_stalls.get(), 1);
    assert!(status.last_error.unwrap().contains("did not advance"));

    manager.stop(camera_id).await.unwrap();
    tokio::fs::remove_dir_all(test_dir).await.unwrap();
}

#[cfg(unix)]
#[tokio::test]
async fn reconnects_when_published_rtsp_output_is_not_ready() {
    let test_dir = std::env::temp_dir().join(format!(
        "video-ingest-agent-readiness-{}",
        uuid::Uuid::new_v4()
    ));
    tokio::fs::create_dir_all(&test_dir).await.unwrap();
    let ffprobe = test_dir.join("fake-ffprobe.sh");
    let ffmpeg = test_dir.join("fake-ffmpeg.sh");

    write_executable(
        &ffprobe,
        "#!/bin/sh\ncase \"$*\" in\n  *mediamtx.test*) printf 'output unavailable\\n' >&2; exit 1 ;;\nesac\nprintf '%s\\n' '{\"streams\":[{\"codec_name\":\"h264\",\"width\":640,\"height\":360,\"avg_frame_rate\":\"15/1\"}]}'\n",
    )
    .await;
    write_executable(
        &ffmpeg,
        "#!/bin/sh\nprintf 'out_time_us=1000000\\nprogress=continue\\n'\nread _\n",
    )
    .await;

    let metrics = Metrics::new().unwrap();
    let manager = PipelineManager::new(
        Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            agent_id: "test-agent".into(),
            api_token: None,
            data_dir: test_dir.clone(),
            ffmpeg_bin: ffmpeg.to_string_lossy().into_owned(),
            ffprobe_bin: ffprobe.to_string_lossy().into_owned(),
            probe_timeout: Duration::from_secs(1),
            ready_timeout: Duration::from_secs(1),
            output_ready_timeout: Duration::from_millis(100),
            output_health_interval: Duration::from_secs(1),
            output_stall_timeout: Duration::from_secs(1),
            max_pipelines: 8,
            max_concurrent_probes: 4,
        },
        metrics.clone(),
    );
    let camera_id = uuid::Uuid::new_v4();
    manager
        .start(StartPipelineRequest {
            command_id: uuid::Uuid::new_v4(),
            camera_id,
            rtsp_url: "rtsp://camera.test/live".into(),
            transport: RtspTransport::Tcp,
            video_mode: VideoMode::Copy,
            output: Output::Rtsp {
                url: "rtsp://mediamtx.test:8554/camera".into(),
            },
            reconnect: true,
        })
        .await
        .unwrap();

    let status = tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            let status = manager.get(camera_id).await.unwrap();
            if status.state == PipelineState::Reconnecting {
                break status;
            }
            sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("pipeline did not enter RECONNECTING after output readiness timeout");
    assert_eq!(status.restart_count, 1);
    assert_eq!(metrics.output_readiness_successes.get(), 0);
    assert_eq!(metrics.output_readiness_failures.get(), 1);
    assert!(status
        .last_error
        .unwrap()
        .contains("published RTSP output"));

    manager.stop(camera_id).await.unwrap();
    tokio::fs::remove_dir_all(test_dir).await.unwrap();
}

#[cfg(unix)]
#[tokio::test]
async fn reconnects_when_running_rtsp_output_disappears() {
    let test_dir = std::env::temp_dir().join(format!(
        "video-ingest-agent-output-health-{}",
        uuid::Uuid::new_v4()
    ));
    tokio::fs::create_dir_all(&test_dir).await.unwrap();
    let ffprobe = test_dir.join("fake-ffprobe.sh");
    let ffmpeg = test_dir.join("fake-ffmpeg.sh");
    let output_probe_count = test_dir.join("output-probe-count");

    write_executable(
        &ffprobe,
        &format!(
            "#!/bin/sh\ncase \"$*\" in\n  *mediamtx.test*) count=$(cat '{}' 2>/dev/null || printf 0); count=$((count + 1)); printf '%s' \"$count\" > '{}'; if [ \"$count\" -gt 1 ]; then printf 'output disappeared\\n' >&2; exit 1; fi ;;\nesac\nprintf '%s\\n' '{{\"streams\":[{{\"codec_name\":\"h264\",\"width\":640,\"height\":360,\"avg_frame_rate\":\"15/1\"}}]}}'\n",
            output_probe_count.display(),
            output_probe_count.display()
        ),
    )
    .await;
    write_executable(
        &ffmpeg,
        "#!/bin/sh\nprintf 'out_time_us=1000000\nprogress=continue\n'\nread _\n",
    )
    .await;

    let metrics = Metrics::new().unwrap();
    let manager = PipelineManager::new(
        Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            agent_id: "test-agent".into(),
            api_token: None,
            data_dir: test_dir.clone(),
            ffmpeg_bin: ffmpeg.to_string_lossy().into_owned(),
            ffprobe_bin: ffprobe.to_string_lossy().into_owned(),
            probe_timeout: Duration::from_secs(1),
            ready_timeout: Duration::from_secs(1),
            output_ready_timeout: Duration::from_secs(1),
            output_health_interval: Duration::from_millis(50),
            output_stall_timeout: Duration::from_secs(1),
            max_pipelines: 8,
            max_concurrent_probes: 4,
        },
        metrics.clone(),
    );
    let camera_id = uuid::Uuid::new_v4();
    manager
        .start(StartPipelineRequest {
            command_id: uuid::Uuid::new_v4(),
            camera_id,
            rtsp_url: "rtsp://camera.test/live".into(),
            transport: RtspTransport::Tcp,
            video_mode: VideoMode::Copy,
            output: Output::Rtsp {
                url: "rtsp://mediamtx.test:8554/camera".into(),
            },
            reconnect: true,
        })
        .await
        .unwrap();

    let status = tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            let status = manager.get(camera_id).await.unwrap();
            if status.state == PipelineState::Reconnecting {
                break status;
            }
            sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("pipeline did not reconnect after published output disappeared");
    assert_eq!(status.restart_count, 1);
    assert_eq!(metrics.output_health_failures.get(), 2);
    assert!(status.last_error.unwrap().contains("became unavailable"));

    manager.stop(camera_id).await.unwrap();
    tokio::fs::remove_dir_all(test_dir).await.unwrap();
}

#[cfg(unix)]
async fn write_executable(path: &Path, contents: &str) {
    tokio::fs::write(path, contents).await.unwrap();
    tokio::fs::set_permissions(path, Permissions::from_mode(0o700))
        .await
        .unwrap();
}
