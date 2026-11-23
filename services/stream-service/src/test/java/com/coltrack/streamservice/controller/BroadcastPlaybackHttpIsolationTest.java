package com.coltrack.streamservice.controller;

import com.coltrack.identity.security.TenantAuthorization;
import com.coltrack.streamservice.config.BroadcastProperties;
import com.coltrack.streamservice.model.Broadcast;
import com.coltrack.streamservice.model.BroadcastVisibility;
import com.coltrack.streamservice.security.BroadcastRateLimiter;
import com.coltrack.streamservice.service.BroadcastService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
        BroadcastPlaybackController controller = new BroadcastPlaybackController(service, properties,
                new BroadcastRateLimiter(), clientBuilder,
                new TenantAuthorization((principal, tenant) -> Optional.empty(), (principal, tenant, permission) -> false));
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }
}
