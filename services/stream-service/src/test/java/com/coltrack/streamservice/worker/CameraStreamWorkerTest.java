package com.coltrack.streamservice.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraStreamWorkerTest {

    @TempDir
    Path directory;

    @Test
    void playlistIsNotReadyUntilReferencedSegmentExists() throws Exception {
        Path playlist = directory.resolve("index.m3u8");
        Files.writeString(playlist, "#EXTM3U\n#EXTINF:2.0,\nsegment00000.ts\n");

        assertFalse(CameraStreamWorker.isPlaylistReady(playlist));

        Files.writeString(directory.resolve("segment00000.ts"), "video-data");

        assertTrue(CameraStreamWorker.isPlaylistReady(playlist));
    }

    @Test
    void emptyOrHeaderOnlyPlaylistIsNotReady() throws Exception {
        Path playlist = directory.resolve("index.m3u8");
        Files.writeString(playlist, "#EXTM3U\n#EXT-X-VERSION:6\n");

        assertFalse(CameraStreamWorker.isPlaylistReady(playlist));
    }

    @Test
    void playlistCannotReferenceFileOutsideStreamDirectory() throws Exception {
        Path playlist = directory.resolve("index.m3u8");
        Path outside = directory.getParent().resolve("outside.ts");
        Files.writeString(outside, "video-data");
        Files.writeString(playlist, "#EXTM3U\n#EXTINF:2.0,\n../outside.ts\n");

        assertFalse(CameraStreamWorker.isPlaylistReady(playlist));
    }
}
