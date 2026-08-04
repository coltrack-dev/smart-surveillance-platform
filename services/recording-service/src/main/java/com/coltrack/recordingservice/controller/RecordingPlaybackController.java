package com.coltrack.recordingservice.controller;

import com.coltrack.recordingservice.model.RecordingEntity;
import com.coltrack.recordingservice.repository.RecordingRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.server.ResponseStatusException;

import java.net.MalformedURLException;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.UUID;

@RestController
@RequestMapping("/recordings")
@RequiredArgsConstructor
public class RecordingPlaybackController {

    private final RecordingRepository recordingRepository;

    @GetMapping("/{recordingId}/{fileName:.+}")
    public ResponseEntity<Resource> getRecordingFile(
            @PathVariable UUID recordingId,
            @PathVariable String fileName
    ) throws MalformedURLException {

        RecordingEntity recording =
                recordingRepository
                        .findById(recordingId)
                        .orElseThrow(
                                () -> new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Recording not found"
                                )
                        );

        Path storedPath =
                Path.of(
                                recording.getFilePath()
                        )
                        .toAbsolutePath()
                        .normalize();

        /*
         * filePath может содержать:
         *
         * 1. путь к каталогу записи;
         * 2. путь непосредственно к index.m3u8.
         */
        Path recordingDirectory =
                Files.isDirectory(storedPath)
                        ? storedPath
                        : storedPath.getParent();

        if (recordingDirectory == null) {

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Recording directory cannot be resolved"
            );
        }

        Path file =
                recordingDirectory
                        .resolve(fileName)
                        .normalize();

        /*
         * Защита от запросов вида ../../etc/passwd.
         */
        if (!file.startsWith(recordingDirectory)) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid recording file path"
            );
        }

        if (
                !Files.exists(file)
                        ||
                        !Files.isRegularFile(file)
                        ||
                        !Files.isReadable(file)
        ) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Recording file not found"
            );
        }

        Resource resource =
                new UrlResource(
                        file.toUri()
                );

        MediaType mediaType =
                resolveMediaType(fileName);

        return ResponseEntity
                .ok()
                .contentType(mediaType)
                .body(resource);
    }

    private MediaType resolveMediaType(
            String fileName
    ) {

        String lowerName =
                fileName.toLowerCase();

        if (lowerName.endsWith(".m3u8")) {

            return MediaType.parseMediaType(
                    "application/vnd.apple.mpegurl"
            );
        }

        if (lowerName.endsWith(".ts")) {

            return MediaType.parseMediaType(
                    "video/mp2t"
            );
        }

        if (lowerName.endsWith(".mp4")) {

            return MediaType.parseMediaType(
                    "video/mp4"
            );
        }

        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
