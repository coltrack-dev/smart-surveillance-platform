mod api;
mod config;
mod ffmpeg;
mod manager;
mod metrics;
mod model;

use anyhow::Result;
use api::AppState;
use config::Config;
use manager::PipelineManager;
use metrics::Metrics;
use tokio::net::TcpListener;
use tracing::info;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let config = Config::from_env()?;
    let metrics = Metrics::new()?;
    let manager = PipelineManager::new(config.clone(), metrics.clone());
    let app = api::router(AppState {
        config: config.clone(),
        manager: manager.clone(),
        metrics,
    });

    let listener = TcpListener::bind(config.bind).await?;
    info!(agent_id = %config.agent_id, address = %config.bind, "video ingest agent started");

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;
    manager.shutdown().await;
    info!("video ingest agent stopped");
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install SIGTERM handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}

