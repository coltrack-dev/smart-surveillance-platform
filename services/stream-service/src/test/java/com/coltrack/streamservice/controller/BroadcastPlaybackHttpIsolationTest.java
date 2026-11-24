package com.coltrack.streamservice.controller;

import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.config.RestClientConfig;
import com.coltrack.streamservice.client.CameraClient;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.repository.BroadcastRepository;
import com.coltrack.streamservice.security.BroadcastRateLimiter;
import com.coltrack.streamservice.service.BroadcastService;
import com.coltrack.streamservice.service.PlaybackTokenService;
import com.coltrack.streamservice.service.StreamManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BroadcastPlaybackHttpIsolationTest {
    @Test
    void privateGrantRevokedBeforeHttpProxyDoesNotReachMediaMtx() throws Exception {
        UUID publicId = UUID.randomUUID();
        BroadcastService broadcasts = mock(BroadcastService.class);
        RestClient.Builder clientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(clientBuilder).build();
        when(broadcasts.authorizePlayback(eq(publicId), eq("revoked"), any()))
                .thenThrow(new BroadcastService.PrivateBroadcastException());
        MockMvc mvc = mvc(broadcasts, clientBuilder);

        mvc.perform(get("/broadcast-play/{id}/segment.ts", publicId).param("token", "revoked"))
                .andExpect(status().isUnauthorized());

        upstream.verify();
        verify(broadcasts).authorizePlayback(eq(publicId), eq("revoked"), any());
    }

    @Test
    void authorizedHttpPlaylistProxyRewritesEveryMediaReference() throws Exception {
        UUID publicId = UUID.randomUUID(), cameraId = UUID.randomUUID();
        Broadcast broadcast = new Broadcast(UUID.randomUUID(), cameraId, publicId, BroadcastVisibility.PUBLIC,
                true, 1, Instant.now(), Instant.now(), UUID.randomUUID());
        BroadcastService broadcasts = mock(BroadcastService.class);
        when(broadcasts.authorizePlayback(eq(publicId), eq("signed"), any())).thenReturn(broadcast);
        RestClient.Builder clientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(clientBuilder).build();
        upstream.expect(requestTo("http://mediamtx/" + cameraId + "/index.m3u8"))
                .andRespond(withSuccess("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:2,\nsegment.ts\n",
                        MediaType.parseMediaType("application/vnd.apple.mpegurl")));
        MockMvc mvc = mvc(broadcasts, clientBuilder);

        mvc.perform(get("/broadcast-play/{id}/index.m3u8", publicId).param("token", "signed"))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("/broadcast-play/" + publicId + "/key.bin?token=signed")))
                .andExpect(content().string(containsString("/broadcast-play/" + publicId + "/segment.ts?token=signed")));
        upstream.verify();
    }

    @Test
    void validHmacGrantReachesValidatorAndRejectsAbsoluteEncryptionKeyUri() throws Exception {
        UUID publicId = UUID.randomUUID(), cameraId = UUID.randomUUID();
        Broadcast broadcast = new Broadcast(UUID.randomUUID(), cameraId, publicId, BroadcastVisibility.PUBLIC,
                true, 1, Instant.now(), Instant.now());
        BroadcastService service = serviceWithValidGrant(broadcast);
        String validGrant = service.playbackToken(publicId).token();
        RestClient.Builder clientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(clientBuilder).build();
        upstream.expect(requestTo("http://mediamtx/" + cameraId + "/index.m3u8"))
                .andRespond(withSuccess("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://outside.invalid/key\"\n",
                        MediaType.parseMediaType("application/vnd.apple.mpegurl")));

        mvc(service, clientBuilder)
                .perform(get("/broadcast-play/{id}/index.m3u8", publicId).param("token", validGrant))
                .andExpect(status().isBadGateway());

        // The one expected source playlist was fetched; the absolute key target was never requested.
        upstream.verify();
    }

    @Test
    void encodedTraversalAndUnrecognizedUriAttributesFailClosed() {
        UUID publicId = UUID.randomUUID();
        for (String reference : new String[]{
                "%2e%2e%2fsecret", "%252e%252e%252fsecret", "..%2Fsecret", "key%5c..%5csecret", "key.bin?url=x", "key.bin#fragment"
        }) {
            String playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"" + reference + "\"\n";
            assertThrows(ResponseStatusException.class,
                    () -> BroadcastPlaybackController.rewritePlaylist(playlist, publicId, "signed"), reference);
        }
        assertThrows(ResponseStatusException.class, () -> BroadcastPlaybackController.rewritePlaylist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,uri=\"https://outside.invalid/key\"\n", publicId, "signed"));
        assertThrows(ResponseStatusException.class, () -> BroadcastPlaybackController.rewritePlaylist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=key.bin\n", publicId, "signed"));
    }

    @Test
    void playbackProxyRejectsUpstreamRedirectWithoutContactingRedirectTarget() throws Exception {
        AtomicInteger redirectTargetRequests = new AtomicInteger();
        AtomicReference<String> forwardedAuthorization = new AtomicReference<>();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/key", exchange -> {
            redirectTargetRequests.incrementAndGet();
            forwardedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "not-a-playlist".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String redirectedUrl = "http://127.0.0.1:" + target.getAddress().getPort() + "/key";
        source.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", redirectedUrl);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        source.start();
        try {
            UUID publicId = UUID.randomUUID(), cameraId = UUID.randomUUID();
            Broadcast broadcast = new Broadcast(UUID.randomUUID(), cameraId, publicId, BroadcastVisibility.PUBLIC,
                    true, 1, Instant.now(), Instant.now());
            BroadcastService service = serviceWithValidGrant(broadcast);
            String validGrant = service.playbackToken(publicId).token();
            BroadcastProperties properties = new BroadcastProperties();
            properties.setMediaHlsBaseUrl("http://127.0.0.1:" + source.getAddress().getPort());
            properties.setMediaReadUsername("private-reader");
            properties.setMediaReadPassword("private-media-secret");
            RestClient.Builder clientBuilder = new RestClientConfig().broadcastPlaybackRestClientBuilder();
            MockMvc mvc = mvc(service, properties, clientBuilder);

            mvc.perform(get("/broadcast-play/{id}/index.m3u8", publicId).param("token", validGrant))
                    .andExpect(status().isBadGateway());

            assertEquals(0, redirectTargetRequests.get());
            assertNull(forwardedAuthorization.get());
        } finally {
            source.stop(0);
            target.stop(0);
        }
    }

    @Test
    void privateTokenHttpEndpointDoesNotIssueAnonymousGrant() throws Exception {
        UUID publicId = UUID.randomUUID();
        BroadcastService broadcasts = mock(BroadcastService.class);
        when(broadcasts.playbackToken(publicId)).thenThrow(new BroadcastService.PrivateBroadcastException());
        MockMvc mvc = mvc(broadcasts, RestClient.builder());

        mvc.perform(get("/api/playback/{id}/token", publicId))
                .andExpect(status().isUnauthorized());

        verify(broadcasts).playbackToken(publicId);
        verify(broadcasts, never()).playbackToken(eq(publicId), any());
    }

    private static MockMvc mvc(BroadcastService service, RestClient.Builder clientBuilder) {
        BroadcastProperties properties = new BroadcastProperties();
        properties.setMediaHlsBaseUrl("http://mediamtx");
        properties.setMediaReadUsername("reader");
        properties.setMediaReadPassword("isolated-reader-secret");
        return mvc(service, properties, clientBuilder);
    }

    private static MockMvc mvc(BroadcastService service, BroadcastProperties properties, RestClient.Builder clientBuilder) {
        BroadcastPlaybackController controller = new BroadcastPlaybackController(service, properties,
                new BroadcastRateLimiter(), clientBuilder,
                new TenantAuthorization((principal, tenant) -> Optional.empty(), (principal, tenant, permission) -> false));
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private static BroadcastService serviceWithValidGrant(Broadcast broadcast) {
        BroadcastProperties properties = new BroadcastProperties();
        properties.setPlaybackSecret("isolated-playback-signing-secret-at-least-32-bytes");
        PlaybackTokenService tokens = new PlaybackTokenService(properties);
        BroadcastRepository repository = mock(BroadcastRepository.class);
        when(repository.findByPublicId(broadcast.publicId())).thenReturn(Optional.of(broadcast));
        return new BroadcastService(repository, mock(CameraClient.class), mock(StreamManager.class), tokens, properties);
    }
}
