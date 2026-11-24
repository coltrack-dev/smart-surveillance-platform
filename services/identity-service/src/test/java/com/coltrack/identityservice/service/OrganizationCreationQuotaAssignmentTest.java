package com.coltrack.identityservice.service;

import com.coltrack.identity.model.User;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identityservice.model.OrganizationDtos.CreateOrganization;
import com.coltrack.identityservice.repository.IdentityRepository;
import com.coltrack.identityservice.repository.QuotaPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrganizationCreationQuotaAssignmentTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void assignsConfiguredDefaultPlanDuringOrganizationCreation() throws Exception {
        UUID userId = UUID.randomUUID();
        String issuer = "https://issuer.example/realms/streaming";
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IdentityRepository identities = mock(IdentityRepository.class);
        QuotaPlanRepository plans = mock(QuotaPlanRepository.class);
        AtomicReference<UUID> createdTenantId = new AtomicReference<>();
        OrganizationManagementService service = new OrganizationManagementService(jdbc, identities,
                new MockEnvironment().withProperty("identity.quotas.default-plan", "pro"), plans);
        when(identities.findUser(issuer, "subject-1")).thenReturn(Optional.of(new User(userId, issuer,
                "subject-1", "owner@example.test", true, "Owner", true, Instant.now(), Instant.now())));
        doAnswer(invocation -> {
            createdTenantId.set(invocation.getArgument(1));
            return 1;
        }).when(jdbc).update(startsWith("INSERT INTO identity.tenants"), any(Object[].class));

        ResultSet resultSet = mock(ResultSet.class);
        Instant createdAt = Instant.now();
        when(resultSet.getObject("id", UUID.class)).thenAnswer(invocation -> createdTenantId.get());
        when(resultSet.getString("slug")).thenReturn("acme");
        when(resultSet.getString("display_name")).thenReturn("Acme");
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        var organization = service.create(new AuthenticatedPrincipal(issuer, "subject-1",
                "owner@example.test", true, "Owner"), new CreateOrganization("Acme", "acme"));

        assertEquals("acme", organization.slug());
        verify(plans).assignDefault(organization.id(), "PRO");
    }
}
