package com.coltrack.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.oidc")
public record OidcSecurityProperties(String issuerUri, String jwkSetUri, String audience) {
    public void validate() {
        if (blank(issuerUri) || blank(jwkSetUri) || blank(audience)) {
            throw new IllegalStateException(
                    "OIDC_ISSUER_URI, OIDC_JWK_SET_URI and OIDC_AUDIENCE are required in OIDC mode");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
