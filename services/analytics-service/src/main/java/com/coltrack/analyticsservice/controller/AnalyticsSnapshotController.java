package com.coltrack.analyticsservice.controller;

import com.coltrack.analyticsservice.service.AnalyticsSnapshotStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/analytics/snapshots")
@RequiredArgsConstructor
public class AnalyticsSnapshotController {

    private final AnalyticsSnapshotStorageService storageService;

    @GetMapping(
            value = "/{eventId}.jpg",
            produces = MediaType.IMAGE_JPEG_VALUE
    )
    public ResponseEntity<InputStreamResource> getSnapshot(
            @PathVariable UUID eventId
    ) {
        try {
            var snapshot = storageService.download(eventId);

            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .contentLength(snapshot.contentLength())
                    .cacheControl(
                            CacheControl
                                    .maxAge(Duration.ofHours(24))
                                    .cachePublic()
                    )
                    .body(
                            new InputStreamResource(
                                    snapshot.inputStream()
                            )
                    );
        } catch (NoSuchKeyException exception) {
            throw new ResponseStatusException(
                    NOT_FOUND,
                    "Snapshot not found",
                    exception
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ResponseStatusException(
                        NOT_FOUND,
                        "Snapshot not found",
                        exception
                );
            }

            throw exception;
        }
    }
}
