package com.coltrack.recordingservice.controller;

import com.coltrack.recordingservice.service.RecordingPlaybackService;

import lombok.RequiredArgsConstructor;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecordingSourceController {

    private static final MediaType MATROSKA =
            MediaType.parseMediaType(
                    "video/x-matroska"
            );

    private final RecordingPlaybackService
            recordingPlaybackService;

    @GetMapping({
            "/recording-sources/{recordingId}",
            "/recordings/{recordingId}/download"
    })
    public ResponseEntity<Resource> download(
            @PathVariable UUID recordingId
    ) throws MalformedURLException {

        Path source =
                recordingPlaybackService
                        .prepareCombinedSource(
                                recordingId
                        );

        Resource resource =
                new UrlResource(
                        source.toUri()
                );

        long contentLength;

        try {

            contentLength = Files.size(
                    source
            );

        } catch (Exception exception) {

            throw new IllegalStateException(
                    "Unable to determine recording size",
                    exception
            );
        }

        return ResponseEntity
                .ok()
                .contentType(
                        MATROSKA
                )
                .contentLength(
                        contentLength
                )
                .cacheControl(
                        CacheControl.noCache()
                )
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                                + recordingId
                                + ".mkv\""
                )
                .body(
                        resource
                );
    }
}
