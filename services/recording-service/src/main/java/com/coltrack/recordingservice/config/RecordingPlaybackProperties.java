package com.coltrack.recordingservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(
        prefix = "recording.playback"
)
public class RecordingPlaybackProperties {

    /**
     * Корневой каталог временного HLS-кэша.
     */
    private Path cacheDirectory =
            Path.of("data/playback-cache");

    /**
     * Время жизни неиспользуемого кэша.
     */
    private Duration ttl =
            Duration.ofHours(1);

    /**
     * Длина HLS-сегмента.
     */
    private int segmentDurationSeconds = 4;

    /**
     * Путь к FFmpeg.
     */
    private String ffmpegPath = "ffmpeg";
}
