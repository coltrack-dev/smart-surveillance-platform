package com.coltrack.identityservice.controller;

import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identityservice.model.QuotaDtos.OrganizationQuotas;
import com.coltrack.identityservice.service.OrganizationQuotaService;
import org.springframework.http.CacheControl;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

@RestController
public class OrganizationQuotaController {
    private final OrganizationQuotaService quotas;

    public OrganizationQuotaController(OrganizationQuotaService quotas) {
        this.quotas = quotas;
    }

    @GetMapping("/api/organizations/{organizationId}/quotas")
    public ResponseEntity<OrganizationQuotas> get(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID organizationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(quotas.get(principal(jwt), organizationId));
    }

    private static AuthenticatedPrincipal principal(Jwt jwt) {
        if (jwt == null) return null;
        Boolean verified = jwt.getClaim("email_verified");
        return new AuthenticatedPrincipal(jwt.getIssuer() == null ? null : jwt.getIssuer().toString(),
                jwt.getSubject(), jwt.getClaimAsString("email"), Boolean.TRUE.equals(verified),
                jwt.getClaimAsString("name"));
    }
}
