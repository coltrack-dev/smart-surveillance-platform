package com.coltrack.identityservice.service;

import com.coltrack.identity.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrganizationRolePolicyTest {
    @Test
    void adminCanOnlyManageOperatorAndViewerMemberships() {
        OrganizationManagementService.requireCanManage(Role.ADMIN, Role.OPERATOR, Role.VIEWER);
        OrganizationManagementService.requireCanManage(Role.ADMIN, Role.VIEWER, null);
        assertForbidden(() -> OrganizationManagementService.requireCanManage(Role.ADMIN, Role.ADMIN, Role.VIEWER));
        assertForbidden(() -> OrganizationManagementService.requireCanManage(Role.ADMIN, Role.OWNER, null));
        assertForbidden(() -> OrganizationManagementService.requireCanManage(Role.ADMIN, Role.OPERATOR, Role.ADMIN));
    }

    @Test
    void adminCanInviteOnlyOperatorAndViewer() {
        OrganizationManagementService.requireCanInvite(Role.ADMIN, Role.OPERATOR);
        OrganizationManagementService.requireCanInvite(Role.ADMIN, Role.VIEWER);
        assertForbidden(() -> OrganizationManagementService.requireCanInvite(Role.ADMIN, Role.ADMIN));
        assertForbidden(() -> OrganizationManagementService.requireCanInvite(Role.ADMIN, Role.OWNER));
    }

    @Test
    void ownerCanManageAndInviteAllSupportedRoles() {
        for (Role role : Role.values()) {
            OrganizationManagementService.requireCanInvite(Role.OWNER, role);
            OrganizationManagementService.requireCanManage(Role.OWNER, role, role);
        }
    }

    private static void assertForbidden(Runnable operation) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, operation::run);
        assertEquals(403, error.getStatusCode().value());
    }
}
