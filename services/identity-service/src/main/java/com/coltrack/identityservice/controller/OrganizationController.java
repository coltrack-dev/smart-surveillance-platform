package com.coltrack.identityservice.controller;

import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identityservice.model.OrganizationDtos.*;
import com.coltrack.identityservice.service.OrganizationManagementService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
public class OrganizationController {
    private final OrganizationManagementService organizations;

    public OrganizationController(OrganizationManagementService organizations) {
        this.organizations = organizations;
    }

    @PostMapping("/api/organizations")
    public ResponseEntity<Organization> create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateOrganization request) {
        Organization created = organizations.create(principal(jwt), request);
        return ResponseEntity.created(URI.create("/api/organizations/" + created.id()))
                .cacheControl(CacheControl.noStore()).body(created);
    }

    @GetMapping("/api/organizations")
    public List<Organization> list(@AuthenticationPrincipal Jwt jwt) { return organizations.list(principal(jwt)); }

    @GetMapping("/api/organizations/{tenantId}")
    public Organization get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId) {
        return organizations.get(principal(jwt), tenantId);
    }

    @PatchMapping("/api/organizations/{tenantId}")
    public Organization update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId,
                               @RequestBody UpdateOrganization request) {
        return organizations.update(principal(jwt), tenantId, request);
    }

    @GetMapping("/api/organizations/{tenantId}/members")
    public List<Member> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId) {
        return organizations.members(principal(jwt), tenantId);
    }

    @PatchMapping("/api/organizations/{tenantId}/members/{userId}")
    public Member changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId,
                             @PathVariable UUID userId, @RequestBody ChangeRole request) {
        return organizations.changeRole(principal(jwt), tenantId, userId, request);
    }

    @DeleteMapping("/api/organizations/{tenantId}/members/{userId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId,
                                       @PathVariable UUID userId) {
        organizations.remove(principal(jwt), tenantId, userId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/organizations/{tenantId}/leave")
    public ResponseEntity<Void> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId) {
        organizations.leave(principal(jwt), tenantId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/organizations/{tenantId}/invitations")
    public ResponseEntity<Invitation> invite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId,
                                             @RequestBody CreateInvitation request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(organizations.invite(principal(jwt), tenantId, request));
    }

    @GetMapping("/api/organizations/{tenantId}/invitations")
    public List<Invitation> invitations(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId) {
        return organizations.invitations(principal(jwt), tenantId);
    }

    @DeleteMapping("/api/organizations/{tenantId}/invitations/{invitationId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId,
                                       @PathVariable UUID invitationId) {
        organizations.revokeInvitation(principal(jwt), tenantId, invitationId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/invitations/accept")
    public ResponseEntity<AcceptedMembership> accept(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestBody AcceptInvitation request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(organizations.accept(principal(jwt), request));
    }

    private static AuthenticatedPrincipal principal(Jwt jwt) {
        Boolean verified = jwt.getClaim("email_verified");
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) name = jwt.getClaimAsString("preferred_username");
        return new AuthenticatedPrincipal(jwt.getIssuer() == null ? null : jwt.getIssuer().toString(),
                jwt.getSubject(), jwt.getClaimAsString("email"), Boolean.TRUE.equals(verified), name);
    }
}
