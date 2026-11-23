package com.coltrack.websocketservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WebSocketHttpSecurityTest.ProbeController.class, properties = "app.security.mode=oidc")
@Import({StreamingWebSocketHttpSecurityConfiguration.class, WebSocketHttpSecurityTest.ProbeController.class})
class WebSocketHttpSecurityTest {
    @Autowired MockMvc mvc;

    @Test
    void oidcPermitsUpgradeButDeniesUnrelatedHttpRoutes() throws Exception {
        mvc.perform(get("/ws")).andExpect(status().isOk());
        mvc.perform(get("/private-management")).andExpect(status().isForbidden());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/ws") String websocketUpgradeProbe() { return "upgrade"; }
        @GetMapping("/private-management") String managementProbe() { return "private"; }
    }
}

@WebMvcTest(controllers = WebSocketHttpSecurityTest.ProbeController.class, properties = "app.security.mode=legacy")
@Import({LegacyWebSocketHttpSecurityConfiguration.class, WebSocketHttpSecurityTest.ProbeController.class})
class LegacyWebSocketHttpSecurityTest {
    @Autowired MockMvc mvc;

    @Test
    void legacyPermitsTrustedUpgradeButDeniesUnrelatedHttpRoutes() throws Exception {
        mvc.perform(get("/ws")).andExpect(status().isOk());
        mvc.perform(get("/private-management")).andExpect(status().isForbidden());
    }
}
