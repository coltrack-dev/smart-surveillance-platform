#!/bin/sh
set -eu

read_secret() {
    secret_file=$1
    if [ ! -r "$secret_file" ]; then
        echo "Required local Compose secret is unavailable." >&2
        exit 2
    fi
    cat "$secret_file"
}

# Percent-encode every UTF-8 byte so reserved characters in RTSP credentials
# cannot alter URL user-info parsing. Values are read from mounted Compose
# secrets and are never written to stdout/stderr.
urlencode() {
    LC_ALL=C
    export LC_ALL
    value=$1
    escaped=
    while [ -n "$value" ]; do
        byte=${value%"${value#?}"}
        case "$byte" in
            [a-zA-Z0-9._~-]) escaped=${escaped}${byte} ;;
            *)
                hex=$(printf '%s' "$byte" | od -An -v -tx1 | tr -d ' \n')
                escaped=${escaped}%${hex}
                ;;
        esac
        value=${value#?}
    done
    printf '%s' "$escaped"
}

case "${1:-publish}" in
    publish)
        username=$(read_secret /run/secrets/media_publish_user)
        password=$(read_secret /run/secrets/media_publish_password)
        rtsp_url="rtsp://$(urlencode "$username"):$(urlencode "$password")@mediamtx:8554/demo-people"
        log_file=$(mktemp)
        trap 'rm -f "$log_file"' EXIT HUP INT TERM
        if ffmpeg -hide_banner -nostdin -loglevel error \
            -re -stream_loop -1 -i /demo/people.mp4 \
            -map 0:v:0 -c:v copy -an \
            -f rtsp -rtsp_transport tcp "$rtsp_url" >/dev/null 2>"$log_file"; then
            rm -f "$log_file"
            exit 0
        else
            result=$?
            if grep -q '401 Unauthorized' "$log_file"; then
                echo "MediaMTX rejected RTSP publish with HTTP 401; FFmpeg details are suppressed." >&2
            elif grep -q '403 Forbidden' "$log_file"; then
                echo "MediaMTX rejected RTSP publish with HTTP 403; FFmpeg details are suppressed." >&2
            else
                echo "RTSP publisher exited (status $result); FFmpeg details are suppressed." >&2
            fi
            rm -f "$log_file"
            exit "$result"
        fi
        ;;
    probe)
        username=$(read_secret /run/secrets/media_read_user)
        password=$(read_secret /run/secrets/media_read_password)
        rtsp_url="rtsp://$(urlencode "$username"):$(urlencode "$password")@mediamtx:8554/demo-people"
        if ffprobe -v error -rtsp_transport tcp -select_streams v:0 \
            -show_entries stream=codec_name,width,height,r_frame_rate \
            -of default=noprint_wrappers=1 "$rtsp_url" 2>/dev/null; then
            :
        else
            echo "Authenticated RTSP read probe failed; FFprobe details are suppressed." >&2
            exit 1
        fi
        ;;
    duration)
        username=$(read_secret /run/secrets/media_read_user)
        password=$(read_secret /run/secrets/media_read_password)
        seconds=${2:-25}
        case "$seconds" in
            ''|*[!0-9]*) echo "Duration must be an integer number of seconds." >&2; exit 2 ;;
        esac
        rtsp_url="rtsp://$(urlencode "$username"):$(urlencode "$password")@mediamtx:8554/demo-people"
        if ffmpeg -hide_banner -nostdin -loglevel error -rtsp_transport tcp \
            -i "$rtsp_url" -t "$seconds" -map 0:v:0 -c:v copy -f null - \
            >/dev/null 2>&1; then
            echo "Authenticated RTSP read completed for ${seconds}s."
        else
            echo "Authenticated RTSP duration check failed; FFmpeg details are suppressed." >&2
            exit 1
        fi
        ;;
    *)
        echo "Usage: publisher-script {publish|probe|duration [seconds]}" >&2
        exit 2
        ;;
esac
