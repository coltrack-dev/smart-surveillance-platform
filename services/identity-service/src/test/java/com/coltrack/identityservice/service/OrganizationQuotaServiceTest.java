package com.coltrack.identityservice.service;

import com.coltrack.identity.model.Role;
import com.coltrack.identity.model.TenantMembership;
import com.coltrack.identity.model.User;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identityservice.model.QuotaDtos.Limits;
import com.coltrack.identityservice.model.QuotaDtos.OrganizationQuotas;
import com.coltrack.identityservice.model.QuotaDtos.Overrides;
import com.coltrack.identityservice.repository.IdentityRepository;
import com.coltrack.identityservice.repository.QuotaPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrganizationQuotaServiceTest {
    private static final String ISSUER = "https://issuer.example/realms/streaming";
    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private IdentityRepository identities;
    private QuotaPlanRepository quotaPlans;
    private OrganizationQuotaService service;

    @BeforeEach
    void setUp() {
        identities = mock(IdentityRepository.class);
        quotaPlans = mock(QuotaPlanRepository.class);
        service = new OrganizationQuotaService(identities, quotaPlans, "FREE");
        when(identities.findUser(ISSUER, "subject-1")).thenReturn(Optional.of(new User(userId, ISSUER,
                "subject-1", "member@example.test", true, "Member", true, Instant.now(), Instant.now())));
    }

    @Test
    void returnsEffectivePlanAndOverridesOnlyForActiveMember() {
        when(identities.membership(userId, tenantId)).thenReturn(Optional.of(
                new TenantMembership(tenantId, userId, "acme", "Acme", Role.VIEWER)));
        OrganizationQuotas expected = new OrganizationQuotas(tenantId, "PRO", "Pro",
                new Limits(12, 5, 25), new Overrides(12, null, null));
        when(quotaPlans.findEffective(tenantId, "FREE")).thenReturn(Optional.of(expected));

        assertEquals(expected, service.get(principal(), tenantId));
        verify(quotaPlans).findEffective(tenantId, "FREE");
    }

    @Test
    void doesNotRevealQuotaForNonMember() {
        when(identities.membership(userId, tenantId)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.get(principal(), tenantId));

        assertEquals(404, error.getStatusCode().value());
        verify(quotaPlans, never()).findEffective(tenantId, "FREE");
    }

    @Test
    void rejectsMissingAuthenticatedPrincipal() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.get(null, tenantId));

        assertEquals(401, error.getStatusCode().value());
    }

    @Test
    void failsClosedWhenTheConfiguredPlanCannotBeResolved() {
        when(identities.membership(userId, tenantId)).thenReturn(Optional.of(
                new TenantMembership(tenantId, userId, "acme", "Acme", Role.OWNER)));
        when(quotaPlans.findEffective(tenantId, "FREE")).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.get(principal(), tenantId));

        assertEquals(503, error.getStatusCode().value());
    }

    private static AuthenticatedPrincipal principal() {
        return new AuthenticatedPrincipal(ISSUER, "subject-1", "member@example.test", true, "Member");
    }
}
