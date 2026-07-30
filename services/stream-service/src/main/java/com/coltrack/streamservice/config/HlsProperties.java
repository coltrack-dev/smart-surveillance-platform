package com.coltrack.streamservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "stream.hls")
public class HlsProperties {

    /**
     * Корневая директория, где FFmpeg создаёт HLS файлы.
     *
     * Например:
     * ./data/hls
     *
     * Структура:
     *
     * data/hls/
     *   cameraId/
     *      index.m3u8
     *      segment0.ts
     */
    private String path;
}
