package com.coltrack.identityservice.model;

import java.time.Instant;
import java.util.UUID;

public final class OrganizationDtos {
    private OrganizationDtos() {}

    public record CreateOrganization(String name, String slug) {}
    public record UpdateOrganization(String name) {}
    public record Organization(UUID id, String slug, String name, Instant createdAt) {}
    public record Member(UUID userId, String email, String displayName, String role, Instant joinedAt) {}
    public record ChangeRole(String role) {}
    public record CreateInvitation(String email, String role) {}
    public record Invitation(UUID id, UUID tenantId, String email, String role, String status,
                             Instant expiresAt, Instant createdAt, String invitationUrl) {}
    public record AcceptInvitation(String token) {}
    public record AcceptedMembership(UUID tenantId, UUID userId, String role) {}
}
