package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.RecordingPlaybackProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;

import java.time.Instant;

import java.util.Comparator;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecordingPlaybackCacheCleaner {

    private static final String ACCESS_FILE = ".last-access";

    private final RecordingPlaybackProperties properties;

    @Scheduled(fixedDelayString = "${recording.playback.cleanup-delay:10m}")
    public void cleanup() {

        Path root =
                properties
                        .getCacheDirectory()
                        .toAbsolutePath()
                        .normalize();

        if (!Files.isDirectory(root)) {
            return;
        }

        Instant expiration =
                Instant.now().minus(
                        properties.getTtl()
                );

        try (Stream<Path> directories =
                     Files.list(root)) {

            directories
                    .filter(Files::isDirectory)
                    .filter(directory ->
                            isExpired(
                                    directory,
                                    expiration
                            )
                    )
                    .forEach(
                            this::deleteDirectory
                    );

        } catch (IOException exception) {

            log.warn(
                    "Unable to scan playback cache {}",
                    root,
                    exception
            );
        }
    }

    private boolean isExpired(
            Path directory,
            Instant expiration
    ) {

        try {

            Path accessFile =
                    directory.resolve(
                            ACCESS_FILE
                    );

            Instant lastAccess =
                    Files.exists(accessFile)
                            ? Files.getLastModifiedTime(
                            accessFile
                    ).toInstant()
                            : Files.getLastModifiedTime(
                            directory
                    ).toInstant();

            return lastAccess.isBefore(
                    expiration
            );

        } catch (IOException exception) {

            log.warn(
                    "Unable to inspect playback cache {}",
                    directory,
                    exception
            );

            return false;
        }
    }

    private void deleteDirectory(
            Path directory
    ) {

        try (Stream<Path> paths =
                     Files.walk(directory)) {

            paths.sorted(
                            Comparator.reverseOrder()
                    )
                    .forEach(path -> {

                        try {

                            Files.deleteIfExists(
                                    path
                            );

                        } catch (IOException exception) {

                            log.warn(
                                    "Unable to delete cache path {}",
                                    path,
                                    exception
                            );
                        }
                    });

            log.info(
                    "Deleted expired playback cache {}",
                    directory
            );

        } catch (IOException exception) {

            log.warn(
                    "Unable to delete playback cache {}",
                    directory,
                    exception
            );
        }
    }
}
