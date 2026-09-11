//! Точка входа в `video-ingest-agent`.
//!
//! Этот модуль собирает приложение из отдельных частей:
//! конфигурации, HTTP API, менеджера FFmpeg-процессов и метрик.
//! Здесь почти нет бизнес-логики — она находится в соответствующих модулях.

// `mod` объявляет модули текущего crate (аналога модуля/артефакта в Java).
// Компилятор найдёт их в соседних файлах `api.rs`, `config.rs` и т. д.
mod api;
mod config;
mod ffmpeg;
mod manager;
mod metrics;
mod model;
mod supervisor;

use anyhow::Result;
use api::AppState;
use config::Config;
use manager::PipelineManager;
use metrics::Metrics;
use tokio::net::TcpListener;
use tracing::info;
use tracing_subscriber::EnvFilter;

/// `#[tokio::main]` превращает асинхронную функцию `main` в обычную точку
/// входа и автоматически создаёт многопоточный Tokio runtime.
/// Runtime выполняет async-задачи: HTTP-запросы, ожидание процессов и сигналы.
#[tokio::main]
async fn main() -> Result<()> {
    // Настраиваем структурированные логи. Уровень берётся из RUST_LOG,
    // например `RUST_LOG=debug`; без переменной используется `info`.
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    // Оператор `?` немедленно возвращает ошибку из main, если операция
    // завершилась неуспешно. Это компактный аналог проверки Result и return.
    let config = Config::from_env()?;
    // Агент завершается сразу с понятной ошибкой, если runtime-образ не содержит
    // FFmpeg/ffprobe. Иначе проблема обнаружилась бы только при старте камеры.
    ffmpeg::verify_binary(&config.ffmpeg_bin).await?;
    ffmpeg::verify_binary(&config.ffprobe_bin).await?;
    tokio::fs::create_dir_all(config.data_dir.join("hls")).await?;
    let metrics = Metrics::new()?;

    // Config и Metrics клонируются не обязательно как полные независимые
    // объекты: внутренние типы Prometheus используют разделяемое состояние.
    // Менеджер также Clone, потому что хранит общее состояние внутри Arc.
    let manager = PipelineManager::new(config.clone(), metrics.clone());
    let app = api::router(AppState {
        config: config.clone(),
        manager: manager.clone(),
        metrics,
    });

    // `.await` приостанавливает только текущую async-задачу, а не поток ОС.
    let listener = TcpListener::bind(config.bind).await?;
    info!(agent_id = %config.agent_id, address = %config.bind, "video ingest agent started");

    // Сервер принимает запросы до SIGINT/SIGTERM. После прекращения приёма
    // запросов явно останавливаем все дочерние FFmpeg-процессы.
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;
    manager.shutdown().await;
    info!("video ingest agent stopped");
    Ok(())
}

/// Ожидает Ctrl+C (SIGINT) либо SIGTERM, который обычно посылает Docker.
async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    // `cfg` — условная компиляция: этот блок существует только на Unix.
    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install SIGTERM handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    // `select!` одновременно ожидает несколько async-операций и продолжает
    // выполнение по первой завершившейся ветке.
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
