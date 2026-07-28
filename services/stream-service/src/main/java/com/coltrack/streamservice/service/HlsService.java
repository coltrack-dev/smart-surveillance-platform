package com.coltrack.streamservice.service;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;


@Service
public class HlsService {

    private final Path hlsRoot;

    public HlsService(@Value("${stream.hls.path:/tmp/hls}") String hlsPath
    ) {
        this.hlsRoot =
                Path.of(hlsPath);
    }


    /**
     * Создает директорию для камеры
     */
    public Path createStreamDirectory(UUID cameraId) {
        try {

            Path cameraPath = hlsRoot.resolve(cameraId.toString());

            Files.createDirectories(cameraPath);

            return cameraPath;

        } catch (Exception e) {
            throw new RuntimeException("Cannot create HLS directory", e);
        }
    }


    /**
     * Получить путь playlist.m3u8
     */
    public Path getPlaylistPath(UUID cameraId) {
        return hlsRoot
                .resolve(
                        cameraId.toString()
                )
                .resolve(
                        "index.m3u8"
                );

    }


    /**
     * URL для просмотра
     */
    public String getStreamUrl(UUID cameraId) {
        return "/hls/"
                + cameraId
                + "/index.m3u8";
    }


    /**
     * Очистка старого потока
     */
    public void deleteStream(UUID cameraId) {

        try {

            Path path =
                    hlsRoot.resolve(
                            cameraId.toString()
                    );

            if (Files.exists(path)) {

                Files.walk(path)
                        .sorted(
                                (a, b) ->
                                        b.compareTo(a)
                        )
                        .forEach(
                                p -> {

                                    try {

                                        Files.delete(p);

                                    } catch (Exception ignored) {

                                    }

                                }
                        );
            }

        } catch (Exception e) {
            throw new RuntimeException("Cannot delete HLS stream", e);
        }
    }
}
