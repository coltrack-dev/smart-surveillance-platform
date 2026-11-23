package com.coltrack.identityservice.controller;

import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.model.User;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identity.security.TenantContext;
import com.coltrack.identityservice.service.IdentityDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/identity")
public class IdentityController {
    private final IdentityDirectory directory;

    public IdentityController(IdentityDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/me")
    public User me(@AuthenticationPrincipal Jwt jwt) {
        return directory.discover(principal(jwt));
    }

    @GetMapping("/me/memberships")
    public List<TenantMembership> memberships(@AuthenticationPrincipal Jwt jwt) {
        return directory.memberships(principal(jwt));
    }

    @GetMapping("/tenants/{tenantId}/membership")
    public TenantContext selectedMembership(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable UUID tenantId) {
        return directory.resolve(principal(jwt), tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static AuthenticatedPrincipal principal(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        Boolean verified = jwt.getClaim("email_verified");
        return new AuthenticatedPrincipal(issuer, jwt.getSubject(), jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(verified),
                firstNonBlank(jwt.getClaimAsString("name"), jwt.getClaimAsString("preferred_username")));
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
