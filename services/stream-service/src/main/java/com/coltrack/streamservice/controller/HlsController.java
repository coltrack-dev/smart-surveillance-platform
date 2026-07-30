package com.coltrack.streamservice.controller;

import com.coltrack.streamservice.config.HlsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/hls")
@RequiredArgsConstructor
public class HlsController {

    private final HlsProperties properties;

    /**
     * Отдаёт HLS playlist и TS сегменты.
     *
     * Примеры:
     *
     * /hls/{cameraId}/index.m3u8
     * /hls/{cameraId}/index74.ts
     */
    @GetMapping("/{cameraId}/{fileName}")
    public ResponseEntity<Resource> getFile(
            @PathVariable String cameraId,
            @PathVariable String fileName
    ) throws Exception {

        Path file =
                Path.of(
                        properties.getPath(),
                        cameraId,
                        fileName
                ).normalize();

        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource =
                new UrlResource(
                        file.toUri()
                );

        MediaType mediaType =
                fileName.endsWith(".m3u8")
                        ? MediaType.parseMediaType(
                        "application/vnd.apple.mpegurl"
                )
                        : MediaType.parseMediaType(
                        "video/mp2t"
                );

        return ResponseEntity
                .ok()
                .contentType(mediaType)
                .body(resource);
    }
}
