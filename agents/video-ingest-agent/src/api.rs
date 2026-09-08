use std::sync::Arc;

use axum::{
    extract::{Path, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::Serialize;
use tower_http::services::ServeDir;
use uuid::Uuid;

use crate::{
    config::Config,
    manager::{ManagerError, PipelineManager},
    metrics::Metrics,
    model::StartPipelineRequest,
};

#[derive(Clone)]
pub struct AppState {
    pub config: Config,
    pub manager: PipelineManager,
    pub metrics: Metrics,
}

pub fn router(state: AppState) -> Router {
    let hls_dir = state.config.data_dir.join("hls");
    Router::new()
        .route("/health", get(health))
        .route("/metrics", get(metrics))
        .route("/v1/pipelines", post(start).get(list))
        .route("/v1/pipelines/{camera_id}", get(get_one).delete(stop))
        .nest_service("/hls", ServeDir::new(hls_dir))
        .with_state(Arc::new(state))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct HealthResponse {
    status: &'static str,
    agent_id: String,
}

async fn health(State(state): State<Arc<AppState>>) -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "UP",
        agent_id: state.config.agent_id.clone(),
    })
}

async fn metrics(State(state): State<Arc<AppState>>) -> Response {
    match state.metrics.encode() {
        Ok(body) => (
            StatusCode::OK,
            [(header::CONTENT_TYPE, "text/plain; version=0.0.4")],
            body,
        )
            .into_response(),
        Err(error) => ApiError::internal(error.to_string()).into_response(),
    }
}

async fn start(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(request): Json<StartPipelineRequest>,
) -> Result<impl IntoResponse, ApiError> {
    authorize(&state.config, &headers)?;
    let status = state.manager.start(request).await?;
    Ok((StatusCode::ACCEPTED, Json(status)))
}

async fn list(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, ApiError> {
    authorize(&state.config, &headers)?;
    Ok(Json(state.manager.list().await))
}

async fn get_one(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(camera_id): Path<Uuid>,
) -> Result<impl IntoResponse, ApiError> {
    authorize(&state.config, &headers)?;
    Ok(Json(state.manager.get(camera_id).await?))
}

async fn stop(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(camera_id): Path<Uuid>,
) -> Result<impl IntoResponse, ApiError> {
    authorize(&state.config, &headers)?;
    Ok(Json(state.manager.stop(camera_id).await?))
}

fn authorize(config: &Config, headers: &HeaderMap) -> Result<(), ApiError> {
    let Some(expected) = &config.api_token else {
        return Ok(());
    };
    let supplied = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "));
    if supplied == Some(expected.as_str()) {
        Ok(())
    } else {
        Err(ApiError::new(StatusCode::UNAUTHORIZED, "unauthorized"))
    }
}

struct ApiError {
    status: StatusCode,
    message: String,
}

impl ApiError {
    fn new(status: StatusCode, message: impl Into<String>) -> Self {
        Self {
            status,
            message: message.into(),
        }
    }

    fn internal(message: impl Into<String>) -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, message)
    }
}

impl From<ManagerError> for ApiError {
    fn from(error: ManagerError) -> Self {
        let status = match error {
            ManagerError::AlreadyExists(_) => StatusCode::CONFLICT,
            ManagerError::NotFound(_) => StatusCode::NOT_FOUND,
            ManagerError::Invalid(_) => StatusCode::BAD_REQUEST,
        };
        Self::new(status, error.to_string())
    }
}

#[derive(Serialize)]
struct ErrorBody {
    error: String,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(ErrorBody { error: self.message })).into_response()
    }
}
