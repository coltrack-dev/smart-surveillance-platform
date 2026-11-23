package com.coltrack.streamservice.controller;

import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.security.BroadcastRateLimiter;
import com.coltrack.streamservice.service.BroadcastService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void capsConcurrentUpstreamRequestsAt32AndReleasesSlotsAfterCompletion() throws Exception {
        UUID publicId = UUID.randomUUID();
        Broadcast broadcast = new Broadcast(UUID.randomUUID(), UUID.randomUUID(), publicId,
                BroadcastVisibility.PUBLIC, true, 1, Instant.now(), Instant.now());
        BroadcastService service = mock(BroadcastService.class);
        when(service.authorizePlayback(eq(publicId), eq("signed"), any())).thenReturn(broadcast);

        CountDownLatch enteredUpstream = new CountDownLatch(32);
        CountDownLatch releaseUpstream = new CountDownLatch(1);
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        BroadcastProperties properties = new BroadcastProperties();
        properties.setMediaHlsBaseUrl("http://mediamtx");
        properties.setMediaReadUsername("reader");
        properties.setMediaReadPassword("read-secret");

        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any())).thenReturn(headersSpec);
        when(headersSpec.headers(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(byte[].class)).thenAnswer(invocation -> {
            enteredUpstream.countDown();
            if (!releaseUpstream.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out waiting to release test response");
            return new byte[]{1, 2, 3};
        });

        BroadcastPlaybackController controller = new BroadcastPlaybackController(
                service, properties, new BroadcastRateLimiter(), builder);
        ExecutorService workers = Executors.newFixedThreadPool(32);
        try {
            List<Future<Integer>> requests = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                requests.add(workers.submit(() -> status(controller, publicId)));
            }
            assertTrue(enteredUpstream.await(5, TimeUnit.SECONDS), "all 32 slots must be occupied upstream");

            ResponseStatusException overflow = assertThrows(ResponseStatusException.class,
                    () -> controller.file(publicId, "segment.ts", "signed", request()));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, overflow.getStatusCode());

            releaseUpstream.countDown();
            for (Future<Integer> request : requests) assertEquals(200, request.get(5, TimeUnit.SECONDS));
            assertEquals(200, status(controller, publicId), "a released slot must admit the next request");
        } finally {
            releaseUpstream.countDown();
            workers.shutdownNow();
        }
    }

    private static int status(BroadcastPlaybackController controller, UUID publicId) throws Exception {
        return controller.file(publicId, "segment.ts", "signed", request()).getStatusCode().value();
    }

    private static HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
