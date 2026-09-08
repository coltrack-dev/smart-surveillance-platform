FROM rust:1.89-bookworm AS builder
WORKDIR /build
COPY Cargo.toml Cargo.lock* ./
COPY src ./src
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates ffmpeg \
    && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/target/release/video-ingest-agent /usr/local/bin/video-ingest-agent
RUN useradd --system --uid 10001 --create-home agent \
    && mkdir -p /data \
    && chown agent:agent /data
USER agent
ENV AGENT_BIND=0.0.0.0:8098 \
    AGENT_DATA_DIR=/data \
    RUST_LOG=info
EXPOSE 8098
ENTRYPOINT ["video-ingest-agent"]
