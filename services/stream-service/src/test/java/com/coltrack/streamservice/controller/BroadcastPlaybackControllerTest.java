package com.coltrack.streamservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BroadcastPlaybackControllerTest {
    @Test
    void rewritesPlaylistSegmentsAndEncryptionKeyToProtectedUrls() {
        UUID id = UUID.randomUUID();
        String playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:2,\nsegment01.ts\n";

        String result = BroadcastPlaybackController.rewritePlaylist(playlist, id, "signed-token");

        assertTrue(result.contains("URI=\"/broadcast-play/" + id + "/key.bin?token=signed-token\""));
        assertTrue(result.contains("/broadcast-play/" + id + "/segment01.ts?token=signed-token"));
    }

    @Test
    void rejectsExternalPlaylistReferences() {
        assertThrows(ResponseStatusException.class, () ->
                BroadcastPlaybackController.rewritePlaylist("#EXTM3U\nhttps://outside.example/segment.ts\n", UUID.randomUUID(), "token"));
    }

    @Test
    void rejectsUnrecognizedKeyUriSyntaxInsteadOfLeavingItUnprotected() {
        assertThrows(ResponseStatusException.class, () ->
                BroadcastPlaybackController.rewritePlaylist("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=key.bin\n", UUID.randomUUID(), "token"));
    }
}
