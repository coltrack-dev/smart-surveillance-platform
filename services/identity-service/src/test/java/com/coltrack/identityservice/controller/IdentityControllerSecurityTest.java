package com.coltrack.identityservice.controller;

import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.model.User;
import com.coltrack.identityservice.config.SecurityConfiguration;
import com.coltrack.identityservice.service.IdentityDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = IdentityController.class, properties = {
        "app.security.oidc.issuer-uri=https://issuer.example/realms/streaming",
        "app.security.oidc.jwk-set-uri=https://issuer.example/realms/streaming/protocol/openid-connect/certs",
        "app.security.oidc.audience=streaming-api"
})
@Import({SecurityConfiguration.class, IdentityControllerSecurityTest.TestHealthController.class})
class IdentityControllerSecurityTest {
    private static final String ISSUER = "https://issuer.example/realms/streaming";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    IdentityDirectory directory;

    @Test
    void permitsAnonymousHealthAndNestedHealthEndpoints() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void rejectsAnonymousIdentityDiscovery() throws Exception {
        mvc.perform(get("/api/identity/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsMembershipsForValidatedJwtPrincipal() throws Exception {
        when(directory.memberships(any())).thenReturn(List.<TenantMembership>of());

        mvc.perform(get("/api/identity/me/memberships")
                        .with(jwt().jwt(token -> token.issuer(ISSUER).subject("subject-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(directory).memberships(org.mockito.ArgumentMatchers.argThat(principal ->
                ISSUER.equals(principal.issuer()) && "subject-1".equals(principal.subject())));
    }

    @Test
    void returnsDiscoveredUserFromAuthenticatedIdentity() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        when(directory.discover(any())).thenReturn(new User(userId, ISSUER, "subject-1", null,
                false, null, true, now, now));

        mvc.perform(get("/api/identity/me")
                        .with(jwt().jwt(token -> token.issuer(ISSUER).subject("subject-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.issuer").value(ISSUER))
                .andExpect(jsonPath("$.subject").value("subject-1"));
    }

    @RestController
    static class TestHealthController {
        @GetMapping({"/actuator/health", "/actuator/health/{component}"})
        Map<String, String> health() {
            return Map.of("status", "UP");
        }
    }
}
