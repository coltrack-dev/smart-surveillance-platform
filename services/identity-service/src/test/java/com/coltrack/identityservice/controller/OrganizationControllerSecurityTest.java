package com.coltrack.identityservice.controller;

import com.coltrack.identityservice.config.SecurityConfiguration;
import com.coltrack.identityservice.model.OrganizationDtos.Organization;
import com.coltrack.identityservice.service.OrganizationManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrganizationController.class, properties = {
        "app.security.oidc.issuer-uri=https://issuer.example/realms/streaming",
        "app.security.oidc.jwk-set-uri=https://issuer.example/realms/streaming/protocol/openid-connect/certs",
        "app.security.oidc.audience=streaming-api"
})
@Import(SecurityConfiguration.class)
class OrganizationControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean OrganizationManagementService organizations;

    @Test
    void rejectsAnonymousOrganizationCreation() throws Exception {
        mvc.perform(post("/api/organizations").contentType("application/json")
                        .content("{\"name\":\"Acme\",\"slug\":\"acme\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsWithTheValidatedOidcPrincipalAndNoStoreResponse() throws Exception {
        UUID id = UUID.randomUUID();
        when(organizations.create(any(), any())).thenReturn(new Organization(id,"acme","Acme",Instant.now()));
        mvc.perform(post("/api/organizations").with(jwt().jwt(token -> token
                                .issuer("https://issuer.example/realms/streaming").subject("person-1")
                                .claim("email","owner@example.test").claim("email_verified",true)))
                        .contentType("application/json")
                        .content("{\"name\":\"Acme\",\"slug\":\"acme\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        verify(organizations).create(org.mockito.ArgumentMatchers.argThat(principal ->
                        "https://issuer.example/realms/streaming".equals(principal.issuer())
                                && "person-1".equals(principal.subject())
                                && "owner@example.test".equals(principal.email()) && principal.emailVerified()), any());
    }
}
