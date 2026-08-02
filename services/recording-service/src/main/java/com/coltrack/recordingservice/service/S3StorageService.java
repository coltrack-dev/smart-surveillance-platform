package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.S3Properties;
import com.coltrack.recordingservice.model.RecordingSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService implements ObjectStorageService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    private final S3Client s3Client;
    private final S3Properties properties;

    @Override
    public void uploadRecording(RecordingSession session) {

        if (!properties.isEnabled()) {

            log.debug("S3 upload disabled");

            return;
        }

        Path directory =
                Paths.get(session.getFilePath());

        if (!Files.exists(directory)) {

            throw new IllegalStateException(
                    "Recording directory does not exist: " + directory
            );
        }

        try (Stream<Path> files = Files.list(directory)) {

            files.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(file -> {

                        String key =
                                uploadFile(
                                        session,
                                        file
                                );

                        session.getS3Keys().add(key);
                    });

        } catch (IOException e) {

            throw new RuntimeException("Unable to upload recording", e);
        }

        session.setUploaded(true);
        session.setUploadedAt(java.time.Instant.now());

        if (properties.isDeleteLocalAfterUpload()) {

            deleteRecording(session);
        }
    }

    private String uploadFile(RecordingSession session, Path file) {

        String key = buildObjectKey(
                session,
                file.getFileName().toString()
        );

        log.info(
                "Uploading {} -> s3://{}/{}",
                file,
                properties.getBucket(),
                key
        );

        PutObjectRequest request =
                PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .contentType("video/x-matroska")
                        .build();

        s3Client.putObject(
                request,
                RequestBody.fromFile(file)
        );

        return key;
    }

    @Override
    public void deleteRecording(RecordingSession session) {

        Path directory =
                Paths.get(session.getFilePath());

        try (Stream<Path> files = Files.list(directory)) {

            files.sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {

                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Cannot delete {}", path, e);
                        }
                    });

        } catch (IOException e) {

            log.warn(
                    "Cannot cleanup recording directory {}",
                    directory,
                    e
            );
        }

        try {

            Files.deleteIfExists(directory);

        } catch (IOException ignored) {
        }
    }

    private String buildObjectKey(RecordingSession session, String fileName) {

        String prefix = properties.getPrefix();

        if (prefix == null) {
            prefix = "";
        }

        if (!prefix.isBlank() && !prefix.endsWith("/")) {
            prefix += "/";
        }

        return prefix
                + session.getCameraId()
                + "/"
                + DATE_FORMAT.format(session.getStartedAt())
                + "/"
                + session.getId()
                + "/"
                + fileName;
    }
}
