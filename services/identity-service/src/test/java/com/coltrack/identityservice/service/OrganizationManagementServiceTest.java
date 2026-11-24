package com.coltrack.identityservice.service;

import com.coltrack.identity.model.User;
import com.coltrack.identity.security.AuthenticatedPrincipal;
import com.coltrack.identityservice.repository.IdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrganizationManagementServiceTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final String ISSUER = "https://issuer.example/realm";
    private JdbcTemplate jdbc;
    private IdentityRepository identities;
    private OrganizationManagementService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        identities = mock(IdentityRepository.class);
        service = new OrganizationManagementService(jdbc, identities, new org.springframework.core.env.StandardEnvironment());
        User user = new User(OWNER, ISSUER, "owner-subject", "owner@example.test", true,
                "Owner", true, Instant.now(), Instant.now());
        when(identities.findUser(ISSUER, "owner-subject")).thenReturn(Optional.of(user));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void lastActiveOwnerCannotBeRemoved() {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT id FROM identity.tenants")) return List.of(TENANT);
            if (sql.startsWith("SELECT m.role_code")) return List.of("OWNER");
            if (sql.startsWith("SELECT role_code")) return List.of("OWNER");
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.remove(principal(), TENANT, OWNER));

        assertEquals(409, error.getStatusCode().value());
        verify(jdbc, never()).update(eq("UPDATE identity.tenant_memberships SET status='REVOKED',updated_at=now() WHERE tenant_id=? AND user_id=? AND status='ACTIVE'"), any(), any());
    }

    private static AuthenticatedPrincipal principal() {
        return new AuthenticatedPrincipal(ISSUER,"owner-subject","owner@example.test",true,"Owner");
    }
}
