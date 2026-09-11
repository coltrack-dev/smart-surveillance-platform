package com.coltrack.streamservice.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaHlsClientTest {

    @Test
    void selectsMediaPlaylistAndLatestSegment() {
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nmain_stream.m3u8\n";
        String media = "#EXTM3U\n#EXTINF:2.0,\nsegment1.ts\n#EXTINF:2.0,\nsegment2.ts\n";

        assertEquals(
                "main_stream.m3u8",
                MediaHlsClient.safeReference(master, ".m3u8").orElseThrow()
        );
        assertEquals(
                "segment2.ts",
                MediaHlsClient.safeReference(media, ".ts", ".m4s").orElseThrow()
        );
    }

    @Test
    void rejectsExternalAndParentReferences() {
        assertTrue(MediaHlsClient.safeReference("https://example.test/a.m3u8", ".m3u8").isEmpty());
        assertTrue(MediaHlsClient.safeReference("../segment.ts", ".ts").isEmpty());
    }
}
