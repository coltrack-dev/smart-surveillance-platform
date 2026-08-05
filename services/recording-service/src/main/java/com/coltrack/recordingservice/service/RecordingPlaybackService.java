package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.config.RecordingPlaybackProperties;
import com.coltrack.recordingservice.model.RecordingObjectEntity;
import com.coltrack.recordingservice.repository.RecordingObjectRepository;
import com.coltrack.recordingservice.repository.RecordingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedWriter;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import java.time.Instant;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingPlaybackService {

    private static final String PLAYLIST_FILE =
            "index.m3u8";

    private static final String ACCESS_FILE =
            ".last-access";

    private final RecordingRepository recordingRepository;

    private final RecordingObjectRepository
            recordingObjectRepository;

    private final S3StorageService s3StorageService;

    private final RecordingPlaybackProperties properties;

    /**
     * Не позволяет двум запросам одновременно собирать
     * один и тот же playback-кэш.
     */
    private final ConcurrentHashMap<UUID, ReentrantLock> locks =
            new ConcurrentHashMap<>();

    public String preparePlayback(
            UUID recordingId
    ) {

        verifyRecordingExists(
                recordingId
        );

        ReentrantLock lock =
                locks.computeIfAbsent(
                        recordingId,
                        ignored -> new ReentrantLock()
                );

        lock.lock();

        try {

            Path cacheDirectory =
                    resolveCacheDirectory(
                            recordingId
                    );

            Path playlist =
                    cacheDirectory.resolve(
                            PLAYLIST_FILE
                    );

            if (isReadyPlaylist(playlist)) {

                touchCache(
                        cacheDirectory
                );

                log.info(
                        "Using cached playback recordingId={}",
                        recordingId
                );

                return buildPlaybackUrl(
                        recordingId
                );
            }

            /*
             * Удаляем остатки предыдущей неудачной сборки.
             */
            deleteDirectoryRecursively(
                    cacheDirectory
            );

            Files.createDirectories(
                    cacheDirectory
            );

            List<RecordingObjectEntity> objects =
                    recordingObjectRepository
                            .findByRecordingIdOrderBySequenceNumberAsc(
                                    recordingId
                            );

            if (objects.isEmpty()) {

                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Recording contains no uploaded S3 objects"
                );
            }

            List<Path> localFiles =
                    downloadObjects(
                            cacheDirectory,
                            objects
                    );

            Path concatFile =
                    createConcatFile(
                            cacheDirectory,
                            localFiles
                    );

            createHls(
                    concatFile,
                    cacheDirectory
            );

            if (!isReadyPlaylist(playlist)) {

                throw new IllegalStateException(
                        "FFmpeg completed but HLS playlist was not created"
                );
            }

            /*
             * Исходные MKV больше не нужны после подготовки HLS.
             */
            for (Path localFile : localFiles) {

                Files.deleteIfExists(
                        localFile
                );
            }

            Files.deleteIfExists(
                    concatFile
            );

            touchCache(
                    cacheDirectory
            );

            log.info(
                    "Playback cache prepared recordingId={}, objects={}",
                    recordingId,
                    objects.size()
            );

            return buildPlaybackUrl(
                    recordingId
            );

        } catch (IOException exception) {

            throw new IllegalStateException(
                    "Unable to prepare playback for recording "
                            + recordingId,
                    exception
            );

        } finally {

            lock.unlock();

            /*
             * Удаляем lock, если за ним уже никто не ожидает.
             */
            if (!lock.hasQueuedThreads()) {

                locks.remove(
                        recordingId,
                        lock
                );
            }
        }
    }

    public Path resolvePlaybackFile(
            UUID recordingId,
            String fileName
    ) {

        if (
                fileName.contains("/")
                        || fileName.contains("\\")
                        || fileName.contains("..")
        ) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid playback file name"
            );
        }

        Path directory =
                resolveCacheDirectory(
                        recordingId
                );

        Path file =
                directory
                        .resolve(fileName)
                        .normalize();

        if (
                !file.startsWith(directory)
                        || !Files.isRegularFile(file)
                        || !Files.isReadable(file)
        ) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Playback file not found"
            );
        }

        touchCache(
                directory
        );

        return file;
    }

    private void verifyRecordingExists(
            UUID recordingId
    ) {

        if (
                !recordingRepository.existsById(
                        recordingId
                )
        ) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Recording not found"
            );
        }
    }

    private List<Path> downloadObjects(
            Path cacheDirectory,
            List<RecordingObjectEntity> objects
    ) {

        List<Path> localFiles =
                new ArrayList<>(
                        objects.size()
                );

        for (int index = 0;
             index < objects.size();
             index++) {

            RecordingObjectEntity object =
                    objects.get(index);

            String extension =
                    resolveExtension(
                            object.getFileName()
                    );

            Path destination =
                    cacheDirectory.resolve(
                            "source-%05d%s"
                                    .formatted(
                                            index,
                                            extension
                                    )
                    );

            s3StorageService.downloadObject(
                    object.getS3Key(),
                    destination
            );

            localFiles.add(
                    destination
            );
        }

        return localFiles;
    }

    private Path createConcatFile(
            Path cacheDirectory,
            List<Path> localFiles
    ) throws IOException {

        Path concatFile =
                cacheDirectory.resolve(
                        "concat.txt"
                );

        try (BufferedWriter writer =
                     Files.newBufferedWriter(
                             concatFile,
                             StandardCharsets.UTF_8,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING
                     )) {

            for (Path file : localFiles) {

                String normalized =
                        file.toAbsolutePath()
                                .normalize()
                                .toString()
                                .replace(
                                        "'",
                                        "'\\''"
                                );

                writer.write(
                        "file '" + normalized + "'"
                );

                writer.newLine();
            }
        }

        return concatFile;
    }

    private void createHls(
            Path concatFile,
            Path outputDirectory
    ) throws IOException {

        Path playlist =
                outputDirectory.resolve(
                        PLAYLIST_FILE
                );

        Path segments =
                outputDirectory.resolve(
                        "segment-%05d.ts"
                );

        List<String> command =
                List.of(
                        properties.getFfmpegPath(),

                        "-hide_banner",
                        "-loglevel",
                        "warning",

                        "-y",

                        "-f",
                        "concat",

                        "-safe",
                        "0",

                        "-i",
                        concatFile.toString(),

                        "-map",
                        "0:v:0",

                        "-map",
                        "0:a?",

                        /*
                         * Сначала пробуем remux без перекодирования.
                         */
                        "-c",
                        "copy",

                        "-f",
                        "hls",

                        "-hls_time",
                        String.valueOf(
                                properties
                                        .getSegmentDurationSeconds()
                        ),

                        "-hls_list_size",
                        "0",

                        "-hls_playlist_type",
                        "vod",

                        "-hls_flags",
                        "independent_segments",

                        "-hls_segment_filename",
                        segments.toString(),

                        playlist.toString()
                );

        log.info(
                "Preparing playback HLS command={}",
                command
        );

        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(
                                outputDirectory
                                        .resolve("ffmpeg.log")
                                        .toFile()
                        )
                        .start();

        int exitCode;

        try {

            exitCode =
                    process.waitFor();

        } catch (InterruptedException exception) {

            Thread.currentThread()
                    .interrupt();

            process.destroyForcibly();

            throw new IllegalStateException(
                    "Playback preparation interrupted",
                    exception
            );
        }

        if (exitCode != 0) {

            String output =
                    readFfmpegLog(
                            outputDirectory
                    );

            throw new IllegalStateException(
                    "FFmpeg playback preparation failed, exitCode="
                            + exitCode
                            + ", output="
                            + output
            );
        }
    }

    private String readFfmpegLog(
            Path directory
    ) {

        Path logFile =
                directory.resolve(
                        "ffmpeg.log"
                );

        try {

            if (!Files.exists(logFile)) {
                return "";
            }

            return Files.readString(
                    logFile
            );

        } catch (IOException exception) {

            return "Unable to read FFmpeg output: "
                    + exception.getMessage();
        }
    }

    private Path resolveCacheDirectory(
            UUID recordingId
    ) {

        Path root =
                properties
                        .getCacheDirectory()
                        .toAbsolutePath()
                        .normalize();

        return root.resolve(
                        recordingId.toString()
                )
                .normalize();
    }

    private boolean isReadyPlaylist(
            Path playlist
    ) {

        try {

            return Files.isRegularFile(playlist)
                    && Files.size(playlist) > 0
                    && Files.readString(playlist)
                    .contains("#EXT-X-ENDLIST");

        } catch (IOException exception) {

            return false;
        }
    }

    private String buildPlaybackUrl(
            UUID recordingId
    ) {

        return "/recordings/playback/"
                + recordingId
                + "/"
                + PLAYLIST_FILE;
    }

    private void touchCache(
            Path cacheDirectory
    ) {

        try {

            Files.createDirectories(
                    cacheDirectory
            );

            Files.writeString(
                    cacheDirectory.resolve(
                            ACCESS_FILE
                    ),
                    Instant.now().toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

        } catch (IOException exception) {

            log.warn(
                    "Unable to update playback cache access time directory={}",
                    cacheDirectory,
                    exception
            );
        }
    }

    private String resolveExtension(
            String fileName
    ) {

        if (fileName == null) {
            return ".mkv";
        }

        int position =
                fileName.lastIndexOf('.');

        if (
                position < 0
                        || position == fileName.length() - 1
        ) {

            return ".mkv";
        }

        return fileName.substring(
                position
        );
    }

    private void deleteDirectoryRecursively(
            Path directory
    ) throws IOException {

        if (!Files.exists(directory)) {
            return;
        }

        try (var paths = Files.walk(directory)) {

            paths.sorted(
                            java.util.Comparator.reverseOrder()
                    )
                    .forEach(path -> {

                        try {

                            Files.deleteIfExists(
                                    path
                            );

                        } catch (IOException exception) {

                            throw new PlaybackCacheDeleteException(
                                    path,
                                    exception
                            );
                        }
                    });

        } catch (PlaybackCacheDeleteException exception) {

            throw exception.getCause();
        }
    }

    private static final class PlaybackCacheDeleteException
            extends RuntimeException {

        private final IOException cause;

        private PlaybackCacheDeleteException(
                Path path,
                IOException cause
        ) {

            super(
                    "Unable to delete playback cache path "
                            + path,
                    cause
            );

            this.cause = cause;
        }

        @Override
        public synchronized IOException getCause() {
            return cause;
        }
    }
}
