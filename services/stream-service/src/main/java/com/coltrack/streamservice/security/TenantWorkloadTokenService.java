package com.coltrack.streamservice.security;

import com.coltrack.identity.security.TenantWorkloadJwt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.UUID;

@Component
public class TenantWorkloadTokenService {
    private final String encodedSecret;
    private final String securityMode;
    private volatile JwtEncoder encoder;

    public TenantWorkloadTokenService(@Value("${streaming.internal.workload-secret:}") String encodedSecret,
                                      @Value("${app.security.mode:legacy}") String securityMode) {
        this.encodedSecret = encodedSecret;
        this.securityMode = securityMode;
    }

    @PostConstruct
    void validateConfiguration() {
        if ("oidc".equalsIgnoreCase(securityMode)) TenantWorkloadJwt.decodeSecret(encodedSecret);
    }

    public String issue(UUID tenantId) {
        JwtEncoder current = encoder;
        if (current == null) {
            synchronized (this) {
                current = encoder;
                if (current == null) encoder = current = TenantWorkloadJwt.encoder(TenantWorkloadJwt.decodeSecret(encodedSecret));
            }
        }
        return TenantWorkloadJwt.issue(current, tenantId, Instant.now());
    }

    public String issueOwnershipLookup() {
        JwtEncoder current = encoder;
        if (current == null) {
            synchronized (this) {
                current = encoder;
                if (current == null) encoder = current = TenantWorkloadJwt.encoder(TenantWorkloadJwt.decodeSecret(encodedSecret));
            }
        }
        return TenantWorkloadJwt.issueOwnershipLookup(current, Instant.now());
    }
}
