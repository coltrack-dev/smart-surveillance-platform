package com.coltrack.analyticsservice.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analytics/snapshots")
public class AnalyticsSnapshotController {

    private final Path snapshotsDirectory;

    public AnalyticsSnapshotController(
            @Value("${analytics.snapshots.path}")
            String snapshotsPath
    ) {
        this.snapshotsDirectory = Path.of(snapshotsPath)
                .toAbsolutePath()
                .normalize();
    }

    @GetMapping(
            value = "/{eventId}.jpg",
            produces = MediaType.IMAGE_JPEG_VALUE
    )
    public ResponseEntity<Resource> getSnapshot(
            @PathVariable UUID eventId
    ) throws Exception {

        Path snapshot = snapshotsDirectory
                .resolve(eventId + ".jpg")
                .normalize();

        if (!snapshot.startsWith(snapshotsDirectory)
                || !Files.isRegularFile(snapshot)
                || !Files.isReadable(snapshot)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new UrlResource(snapshot.toUri());

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(Files.size(snapshot))
                .body(resource);
    }
}
