package com.coltrack.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebFluxTest(controllers = LegacySecurityConfigurationTest.ProbeController.class,
        properties = "app.security.mode=legacy")
@Import({LegacySecurityConfiguration.class, LegacySecurityConfigurationTest.ProbeController.class})
class LegacySecurityConfigurationTest {
    @Autowired
    WebTestClient client;

    @Test
    void preservesUnauthenticatedTrustedSurveillanceGatewayAccess() {
        client.get().uri("/api/v1/cameras")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void deniesSaaSOrganizationRoutesInLegacySurveillanceMode() {
        client.get().uri("/api/v1/organizations")
                .exchange()
                .expectStatus().isUnauthorized();

        client.post().uri("/api/v1/invitations/accept")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/cameras")
        String cameras() {
            return "legacy route";
        }
    }
}
