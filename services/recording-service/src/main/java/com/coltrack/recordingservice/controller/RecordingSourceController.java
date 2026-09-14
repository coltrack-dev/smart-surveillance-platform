package com.coltrack.recordingservice.controller;

import com.coltrack.recordingservice.service.RecordingPlaybackService;
import com.coltrack.recordingservice.service.RecordingUsageGuard;

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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final RecordingUsageGuard recordingUsageGuard;

    @GetMapping({
            "/recording-sources/{recordingId}",
            "/recordings/{recordingId}/download"
    })
    public ResponseEntity<Resource> download(
            @PathVariable UUID recordingId
    ) throws MalformedURLException {

        RecordingUsageGuard.Lease usageLease = recordingUsageGuard.acquire(
                recordingId, "EXPORT_STREAM", Duration.ofHours(24));

        try {

            Path source =
                    recordingPlaybackService
                            .prepareCombinedSource(
                                    recordingId
                            );

            Resource resource = new LeaseAwareUrlResource(source, usageLease);

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
        } catch (RuntimeException | MalformedURLException exception) {
            usageLease.close();
            throw exception;
        }
    }

    private static final class LeaseAwareUrlResource extends UrlResource {
        private final RecordingUsageGuard.Lease usageLease;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private LeaseAwareUrlResource(Path source, RecordingUsageGuard.Lease usageLease)
                throws MalformedURLException {
            super(source.toUri());
            this.usageLease = usageLease;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            try {
                InputStream input = super.getInputStream();
                return new FilterInputStream(input) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            release();
                        }
                    }
                };
            } catch (IOException exception) {
                release();
                throw exception;
            }
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                usageLease.close();
            }
        }
    }
}
