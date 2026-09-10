package com.coltrack.streamservice.worker;

import com.coltrack.streamservice.model.StreamSession;
import com.coltrack.streamservice.model.VideoProcessingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void usesWallclockTimestampsForRtspInput() throws Exception {
        StreamSession session = StreamSession.builder()
                .cameraId(UUID.randomUUID())
                .rtspUrl("rtsp://nvr.local/live")
                .videoProcessingMode(VideoProcessingMode.TRANSCODE_H264)
                .build();
        CameraStreamWorker worker = new CameraStreamWorker(session, null, null, null);

        List<String> command = worker.buildCommand(directory);
        int inputPosition = command.indexOf("-i");

        assertEquals("-use_wallclock_as_timestamps", command.get(inputPosition - 2));
        assertEquals("1", command.get(inputPosition - 1));
    }
}
