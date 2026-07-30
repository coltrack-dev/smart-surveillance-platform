package com.coltrack.streamservice.service;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;


@Slf4j
@Service
public class HlsService {


    private final Path hlsRoot;


    private static final Duration FILE_RETENTION =
            Duration.ofHours(1);


    public HlsService(
            @Value("${stream.hls.path:/tmp/hls}")
            String hlsPath
    ) {

        this.hlsRoot =
                Path.of(hlsPath);

        log.info(
                "HLS root directory: {}",
                hlsRoot
        );
    }


    /**
     * Creates directory for camera HLS stream.
     */
    public Path createStreamDirectory(
            UUID cameraId
    ) {

        try {

            Path cameraPath =
                    hlsRoot.resolve(
                            cameraId.toString()
                    );


            Files.createDirectories(
                    cameraPath
            );


            log.info(
                    "HLS directory created camera={} path={}",
                    cameraId,
                    cameraPath
            );


            return cameraPath;

        }
        catch (IOException e) {

            log.error(
                    "Cannot create HLS directory camera={}",
                    cameraId,
                    e
            );

            throw new RuntimeException(
                    "Cannot create HLS directory",
                    e
            );
        }
    }


    /**
     * Returns playlist path.
     */
    public Path getPlaylistPath(
            UUID cameraId
    ) {

        return hlsRoot
                .resolve(
                        cameraId.toString()
                )
                .resolve(
                        "index.m3u8"
                );
    }


    /**
     * Returns HLS URL.
     */
    public String getStreamUrl(
            UUID cameraId
    ) {

        return "/hls/"
                + cameraId
                + "/index.m3u8";
    }


    /**
     * Removes old HLS files before FFmpeg restart.
     *
     * Deletes:
     * - index.m3u8
     * - *.ts segments
     */
    public void cleanupStreamDirectory(Path directory) {

        if (!Files.exists(directory)) {
            return;
        }

        log.info("Cleaning HLS directory {}", directory);

        try (Stream<Path> files = Files.list(directory)) {

            files.filter(this::isHlsFile)
                    .forEach(this::deleteFile);
        }
        catch (IOException e) {

            log.error("Failed cleaning HLS directory {}", directory, e
            );
        }
    }


    /**
     * Deletes HLS directory completely.
     *
     * Use only when camera removed.
     */
    public void deleteStream(
            UUID cameraId
    ) {


        Path path =
                hlsRoot.resolve(
                        cameraId.toString()
                );


        if (!Files.exists(path)) {

            return;
        }


        log.info(
                "Deleting HLS stream directory camera={} path={}",
                cameraId,
                path
        );


        try (Stream<Path> files =
                     Files.walk(path)) {


            files
                    .sorted(
                            (a,b) ->
                                    b.compareTo(a)
                    )
                    .forEach(this::deleteFile);


        }
        catch (IOException e) {

            log.error(
                    "Cannot delete HLS stream camera={}",
                    cameraId,
                    e
            );

            throw new RuntimeException(
                    "Cannot delete HLS stream",
                    e
            );
        }
    }


    /**
     * Periodic cleanup.
     *
     * Removes abandoned HLS files:
     * - after crashes;
     * - after service restart;
     * - after camera deletion.
     */
    @Scheduled(
            fixedDelay = 3600000
    )
    public void cleanupOldStreams() {


        log.info(
                "Running scheduled HLS cleanup"
        );


        if (!Files.exists(hlsRoot)) {

            return;
        }


        try (Stream<Path> cameras =
                     Files.list(hlsRoot)) {


            cameras
                    .filter(Files::isDirectory)
                    .forEach(
                            this::cleanupOldFiles
                    );


        }
        catch (IOException e) {

            log.error(
                    "Failed scheduled HLS cleanup",
                    e
            );
        }
    }


    private void cleanupOldFiles(
            Path directory
    ) {


        try (Stream<Path> files =
                     Files.list(directory)) {


            files
                    .filter(this::isHlsFile)
                    .filter(this::isExpired)
                    .forEach(this::deleteFile);


        }
        catch (IOException e) {

            log.error(
                    "Cannot cleanup directory {}",
                    directory,
                    e
            );
        }
    }


    private boolean isExpired(
            Path file
    ) {

        try {

            Instant modified =
                    Files.getLastModifiedTime(file)
                            .toInstant();


            return modified
                    .plus(FILE_RETENTION)
                    .isBefore(
                            Instant.now()
                    );

        }
        catch (IOException e) {

            return false;
        }
    }


    private boolean isHlsFile(
            Path path
    ) {

        String name =
                path.getFileName()
                        .toString();


        return name.endsWith(".m3u8")
                ||
                name.endsWith(".ts");
    }


    private void deleteFile(
            Path file
    ) {

        try {

            Files.deleteIfExists(
                    file
            );


            log.debug(
                    "Deleted HLS file {}",
                    file
            );

        }
        catch (IOException e) {

            log.warn(
                    "Cannot delete HLS file {}",
                    file,
                    e
            );
        }
    }
}
