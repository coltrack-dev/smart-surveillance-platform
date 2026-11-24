package com.coltrack.identityservice.controller;

import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identityservice.config.SecurityConfiguration;
import com.coltrack.identityservice.model.QuotaDtos.Limits;
import com.coltrack.identityservice.model.QuotaDtos.OrganizationQuotas;
import com.coltrack.identityservice.model.QuotaDtos.Overrides;
import com.coltrack.identityservice.service.OrganizationQuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrganizationQuotaController.class, properties = {
        "app.security.oidc.issuer-uri=https://issuer.example/realms/streaming",
        "app.security.oidc.jwk-set-uri=https://issuer.example/realms/streaming/protocol/openid-connect/certs",
        "app.security.oidc.audience=streaming-api"
})
@Import(SecurityConfiguration.class)
class OrganizationQuotaControllerSecurityTest {
    private static final String ISSUER = "https://issuer.example/realms/streaming";
    @Autowired MockMvc mvc;
    @MockitoBean OrganizationQuotaService quotas;

    @Test
    void rejectsAnonymousQuotaRead() throws Exception {
        mvc.perform(get("/api/organizations/{id}/quotas", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsNoStoreEffectiveQuotasForAuthenticatedMember() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(quotas.get(any(AuthenticatedPrincipal.class), any())).thenReturn(new OrganizationQuotas(tenantId,
                "FREE", "Free", new Limits(2, 1, 3), new Overrides(null, null, null)));

        mvc.perform(get("/api/organizations/{id}/quotas", tenantId)
                        .with(jwt().jwt(token -> token.issuer(ISSUER).subject("subject-1")
                                .claim("email", "owner@example.test").claim("email_verified", true))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.planCode").value("FREE"))
                .andExpect(jsonPath("$.limits.maxCameras").value(2))
                .andExpect(jsonPath("$.limits.maxActiveBroadcasts").value(1))
                .andExpect(jsonPath("$.limits.maxConcurrentViewers").value(3));

        verify(quotas).get(org.mockito.ArgumentMatchers.argThat(principal ->
                ISSUER.equals(principal.issuer()) && "subject-1".equals(principal.subject())), org.mockito.ArgumentMatchers.eq(tenantId));
    }
}
