package com.coltrack.streamservice.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.oidc")
public record OidcSecurityProperties(String issuerUri, String jwkSetUri, String audience) {
    public void validate() {
        if (blank(issuerUri) || blank(jwkSetUri) || blank(audience)) {
            throw new IllegalStateException(
                    "OIDC issuer, JWK set URI and audience are required in OIDC mode");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
