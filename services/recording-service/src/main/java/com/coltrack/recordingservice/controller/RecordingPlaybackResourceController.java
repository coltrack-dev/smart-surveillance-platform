package com.coltrack.recordingservice.controller;

import com.coltrack.recordingservice.service.RecordingPlaybackService;

import lombok.RequiredArgsConstructor;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.MalformedURLException;

import java.nio.file.Path;

import java.time.Duration;

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/recordings/playback")
@RequiredArgsConstructor
public class RecordingPlaybackResourceController {

    private final RecordingPlaybackService
            recordingPlaybackService;

    @GetMapping("/{recordingId}/{fileName}")
    public ResponseEntity<Resource> getPlaybackFile(
            @PathVariable UUID recordingId,
            @PathVariable String fileName
    ) throws MalformedURLException {

        Path file =
                recordingPlaybackService
                        .resolvePlaybackFile(
                                recordingId,
                                fileName
                        );

        Resource resource =
                new UrlResource(
                        file.toUri()
                );

        return ResponseEntity
                .ok()
                .contentType(
                        resolveContentType(
                                fileName
                        )
                )
                .cacheControl(
                        resolveCacheControl(
                                fileName
                        )
                )
                .body(resource);
    }

    private MediaType resolveContentType(
            String fileName
    ) {

        String lower =
                fileName.toLowerCase(
                        Locale.ROOT
                );

        if (lower.endsWith(".m3u8")) {

            return MediaType.parseMediaType(
                    "application/vnd.apple.mpegurl"
            );
        }

        if (lower.endsWith(".ts")) {

            return MediaType.parseMediaType(
                    "video/mp2t"
            );
        }

        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private CacheControl resolveCacheControl(
            String fileName
    ) {

        if (
                fileName.toLowerCase(Locale.ROOT)
                        .endsWith(".m3u8")
        ) {

            return CacheControl.noCache();
        }

        /*
         * VOD-сегменты неизменяемы.
         */
        return CacheControl
                .maxAge(
                        Duration.ofHours(24)
                )
                .cachePublic();
    }
}
