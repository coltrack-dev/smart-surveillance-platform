package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.S3Properties;
import com.coltrack.recordingservice.model.RecordingObjectEntity;
import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;

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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService
        implements ObjectStorageService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    private final S3Client s3Client;
    private final S3Properties properties;
    private final RecordingObjectRepository recordingObjectRepository;

    @Override
    public void uploadRecording(RecordingSession session) {

        if (!properties.isEnabled()) {
            log.debug("S3 upload disabled");
            return;
        }

        Path directory = Paths.get(session.getFilePath());

        if (!Files.isDirectory(directory)) {

            throw new IllegalStateException("Recording directory does not exist: " + directory);
        }

        List<Path> files;

        try (Stream<Path> fileStream = Files.list(directory)) {

            files = fileStream
                    .filter(Files::isRegularFile)
                    .filter(this::isRecordingFile)
                    .sorted()
                    .toList();

        } catch (IOException exception) {

            throw new IllegalStateException("Unable to read recording directory: " + directory, exception);
        }

        if (files.isEmpty()) {

            throw new IllegalStateException("Recording directory contains no files: " + directory);
        }

        /*
         * Если метод вызывается повторно для той же сессии,
         * предотвращаем накопление одинаковых runtime-ключей.
         */
        session.getS3Keys().clear();

        for (int sequenceNumber = 0;
             sequenceNumber < files.size();
             sequenceNumber++) {

            Path file =
                    files.get(sequenceNumber);

            uploadAndSaveObject(
                    session,
                    file,
                    sequenceNumber
            );
        }

        session.setUploaded(true);
        session.setUploadedAt(
                Instant.now()
        );

        log.info(
                "Recording upload completed recordingId={}, files={}",
                session.getId(),
                files.size()
        );

        if (
                properties
                        .isDeleteLocalAfterUpload()
        ) {

            deleteRecording(
                    session
            );
        }
    }

    /**
     * Загружает один MKV-файл в S3 и сохраняет
     * сведения о загруженном объекте в БД.
     */
    private void uploadAndSaveObject(
            RecordingSession session,
            Path file,
            int sequenceNumber
    ) {

        String s3Key =
                buildObjectKey(
                        session,
                        file.getFileName()
                                .toString()
                );

        log.info(
                "Uploading {} -> s3://{}/{}",
                file,
                properties.getBucket(),
                s3Key
        );

        PutObjectRequest request =
                PutObjectRequest.builder()
                        .bucket(
                                properties.getBucket()
                        )
                        .key(s3Key)
                        .contentType(
                                "video/x-matroska"
                        )
                        .build();

        s3Client.putObject(
                request,
                RequestBody.fromFile(file)
        );

        /*
         * Сохраняем запись только после успешного putObject.
         */
        saveUploadedObject(
                session,
                s3Key,
                file,
                sequenceNumber
        );
    }

    private void saveUploadedObject(
            RecordingSession session,
            String s3Key,
            Path localFile,
            int sequenceNumber
    ) {

        long fileSize;

        try {

            fileSize = Files.size(localFile);

        } catch (IOException exception) {

            throw new IllegalStateException("Unable to determine recording file size: " + localFile, exception);
        }

        RecordingObjectEntity entity =
                RecordingObjectEntity.builder()
                        .id(UUID.randomUUID())
                        .recordingId(session.getId())
                        .s3Key(s3Key)
                        .fileName(localFile
                                        .getFileName()
                                        .toString()
                        )
                        .sizeBytes(fileSize)
                        .sequenceNumber(sequenceNumber)
                        .uploadedAt(Instant.now())
                        .build();

        recordingObjectRepository.save(entity);

        session.getS3Keys().add(s3Key);

        log.debug(
                "Saved recording object recordingId={}, key={}, sequence={}",
                session.getId(),
                s3Key,
                sequenceNumber
        );
    }

    private boolean isRecordingFile(Path file) {

        String fileName =
                file.getFileName()
                        .toString()
                        .toLowerCase();

        return fileName.endsWith(".mkv");
    }

    @Override
    public void deleteRecording(RecordingSession session) {

        Path directory = Paths.get(session.getFilePath());

        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> files = Files.list(directory)) {

            files.sorted(
                    Comparator.reverseOrder()
                    )
                    .forEach(path -> {

                        try {

                            Files.deleteIfExists(
                                    path
                            );

                        } catch (IOException exception) {

                            log.warn("Cannot delete {}", path, exception);
                        }
                    });

        } catch (IOException exception) {

            log.warn("Cannot cleanup recording directory {}", directory, exception);
        }

        try {

            Files.deleteIfExists(directory);

        } catch (IOException exception) {

            log.warn("Cannot delete recording directory {}", directory, exception);
        }
    }

    private String buildObjectKey(
            RecordingSession session,
            String fileName
    ) {

        String prefix = properties.getPrefix();

        if (prefix == null) {
            prefix = "";
        }

        if (
                !prefix.isBlank() &&
                        !prefix.endsWith("/")
        ) {
            prefix += "/";
        }

        return prefix
                + session.getCameraId()
                + "/"
                + DATE_FORMAT.format(
                session.getStartedAt()
        )
                + "/"
                + session.getId()
                + "/"
                + fileName;
    }
}
