package com.coltrack.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(controllers = StreamingSecurityConfigurationTest.ProbeController.class,
        properties = {
                "app.security.mode=oidc",
                "app.security.oidc.issuer-uri=https://issuer.example/realms/streaming",
                "app.security.oidc.jwk-set-uri=https://issuer.example/realms/streaming/protocol/openid-connect/certs",
                "app.security.oidc.audience=streaming-api"
        })
@Import({StreamingSecurityConfiguration.class, StreamingSecurityConfigurationTest.ProbeController.class})
class StreamingSecurityConfigurationTest {
    @Autowired
    WebTestClient client;

    @Test
    void requiresAuthenticationForManagementRoutes() {
        client.get().uri("/secured")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void authenticatedJwtCanReachManagementRoutes() {
        client.mutateWith(mockJwt())
                .get().uri("/secured")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void deniesLegacyMediaAndWebSocketRoutes() {
        client.mutateWith(mockJwt())
                .get().uri("/media-hls/camera/index.m3u8")
                .exchange()
                .expectStatus().isForbidden();

        client.mutateWith(mockJwt())
                .get().uri("/ws")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void permitsPublicPlaybackRoutesToReachBroadcastPolicy() {
        client.get().uri("/api/playback/public-id/token")
                .exchange()
                .expectStatus().isOk();
    }

    @RestController
    static class ProbeController {
        @GetMapping("/secured")
        String secured() {
            return "ok";
        }

        @GetMapping("/media-hls/camera/index.m3u8")
        String legacyHls() {
            return "must not be reached";
        }

        @GetMapping("/ws")
        String socket() {
            return "must not be reached";
        }

        @GetMapping("/api/playback/public-id/token")
        String publicPlaybackPolicyEndpoint() {
            return "public policy decides";
        }
    }
}
