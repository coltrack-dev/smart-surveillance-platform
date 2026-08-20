package com.coltrack.recordingservice.service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
/**
 * Service responsible for recording file storage.
 *
 * Responsibilities:
 * - create camera recording directories;
 * - cleanup temporary files;
 * - find recorded files;
 * - prepare storage for FFmpeg.
 */
@Slf4j
@Service
public class RecordingStorageService {

    private final Path storageRoot;

    public RecordingStorageService(
            @Value("${recording.storage.path}") String storagePath
    ) {
        this.storageRoot = Path.of(storagePath);
    }

    /**
     * Creates directory for camera recordings.
     */
    public Path createRecordingDirectory(
            UUID cameraId,
            UUID recordingId
    ) {

        LocalDate date = LocalDate.now();

        Path path = storageRoot
                .resolve(cameraId.toString())
                .resolve(date.toString())
                .resolve(recordingId.toString());

        try {
            Files.createDirectories(path);

            log.info(
                    "Recording directory created camera={} path={}",
                    cameraId,
                    path
            );

            return path;

        } catch (IOException e) {
            throw new RuntimeException(
                    "Cannot create recording directory",
                    e
            );
        }
    }

    /**
     * Removes unfinished or temporary recording files.
     *
     * Deletes:
     * - *.tmp
     * - *.part
     */
    public void cleanupDirectory(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        log.info(
                "Cleaning recording directory {}",
                directory
        );
        try (Stream<Path> files = Files.list(directory)) {
            files
                    .filter(this::isTemporaryFile)
                    .forEach(this::deleteFile);
        }
        catch (IOException e) {
            log.error(
                    "Failed cleaning recording directory {}",
                    directory,
                    e
            );
        }
    }
    /**
     * Returns all completed recordings.
     */
    public List<Path> listRecordings(UUID cameraId) {
        Path directory =
                storageRoot.resolve(
                        cameraId.toString()
                );
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(this::isRecordingFile)
                    .sorted(
                            Comparator.comparing(
                                    this::getModifiedTime
                            )
                    )
                    .toList();
        }
        catch (IOException e) {
            log.error(
                    "Failed listing recordings camera={}",
                    cameraId,
                    e
            );
            return List.of();
        }
    }
    /**
     * Returns latest created recording file.
     */
    public Path findLatestRecording(UUID cameraId) {
        return listRecordings(cameraId)
                .stream()
                .max(
                        Comparator.comparing(
                                this::getModifiedTime
                        )
                )
                .orElse(null);
    }
    /**
     * Deletes recordings older than specified time.
     *
     * Later this method can be used by scheduler.
     */
    public void deleteOlderThan(UUID cameraId, Instant limit) {
        listRecordings(cameraId)
                .stream()
                .filter(
                        file -> getModifiedTime(file)
                                .isBefore(limit)
                )
                .forEach(this::deleteFile);
    }
    /**
     * Checks recording file extension.
     */
    private boolean isRecordingFile(Path file) {
        return Files.isRegularFile(file)
                &&
                file.toString()
                        .endsWith(".mp4");
    }
    /**
     * Checks temporary file extension.
     */
    private boolean isTemporaryFile(Path file) {
        String name =
                file.getFileName()
                        .toString();
        return name.endsWith(".tmp")
                ||
                name.endsWith(".part");
    }
    /**
     * Deletes file safely.
     */
    private void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
            log.info(
                    "Deleted recording file {}",
                    file
            );
        }
        catch (IOException e) {
            log.error(
                    "Failed deleting file {}",
                    file,
                    e
            );
        }
    }
    /**
     * Returns file modification time.
     */
    private Instant getModifiedTime(Path file) {
        try {
            return Files.getLastModifiedTime(file)
                    .toInstant();
        }
        catch (IOException e) {
            return Instant.MIN;
        }
    }

    public long calculateDirectorySize(Path dir) throws IOException {

        try (Stream<Path> stream = Files.walk(dir)) {

            long sum = stream
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
            return sum;
        }
    }

    public int countSegments(Path directory) {

        try (Stream<Path> stream = Files.list(directory)) {

            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".mkv"))
                    .count();

        } catch (IOException e) {

            log.warn("Unable to count recording segments {}", directory, e);

            return 0;
        }
    }

    public Path findFirstSegment(Path directory) {

        try (Stream<Path> stream = Files.list(directory)) {

            return stream
                    .filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".mkv"))
                    .sorted()
                    .findFirst()
                    .orElse(null);

        } catch (IOException e) {

            log.warn("Unable to find first recording segment {}", directory, e);

            return null;
        }
    }
}
