package com.coltrack.identity.security;

public record AuthenticatedPrincipal(String issuer, String subject, String email, boolean emailVerified,
                                     String displayName) {
    public AuthenticatedPrincipal {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("issuer and subject are required");
        }
    }
}
