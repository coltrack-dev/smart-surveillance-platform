package com.coltrack.streamservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = StreamingResourceSecurityConfigurationTest.ProbeController.class,
        properties = {
                "app.security.mode=oidc",
                "app.security.oidc.issuer-uri=https://issuer.example/realms/streaming",
                "app.security.oidc.jwk-set-uri=https://issuer.example/realms/streaming/protocol/openid-connect/certs",
                "app.security.oidc.audience=streaming-api"
        })
@Import({StreamingResourceSecurityConfiguration.class,
        StreamingResourceSecurityConfigurationTest.ProbeController.class})
class StreamingResourceSecurityConfigurationTest {
    @Autowired
    MockMvc mvc;

    @Test
    void requiresAuthenticationForManagementRoutes() throws Exception {
        mvc.perform(get("/secured")).andExpect(status().isUnauthorized());
        mvc.perform(get("/secured").with(SecurityMockMvcRequestPostProcessors.jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void permitsPlaybackPolicyRoutesAndRequiresTheirOwnGrant() throws Exception {
        mvc.perform(get("/api/playback/public-id/token")).andExpect(status().isOk());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/secured")
        String secured() {
            return "ok";
        }

        @GetMapping("/api/playback/public-id/token")
        String playbackPolicy() {
            return "Broadcast policy decides";
        }
    }
}
