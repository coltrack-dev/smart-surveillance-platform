package com.coltrack.websocketservice.security;

import com.coltrack.identity.security.AuthenticatedPrincipal;
import java.security.Principal;
import java.util.UUID;

/** A validated identity bound to exactly one membership-selected tenant for this socket. */
public record TenantSocketPrincipal(String issuer, String subject, UUID tenantId) implements Principal {
    @Override
    public String getName() {
        return issuer + "|" + subject + "|" + tenantId;
    }

    public AuthenticatedPrincipal identity() {
        return new AuthenticatedPrincipal(issuer, subject, null, false, null);
    }
}
