package com.coltrack.identity.security;

import com.coltrack.identity.model.Role;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantPermissionsTest {
    @Test
    void ownerAndAdminCanManageResources() {
        for (Role role : new Role[]{Role.OWNER, Role.ADMIN}) {
            assertTrue(TenantPermissions.allows(role, "camera.manage"));
            assertTrue(TenantPermissions.allows(role, "stream.manage"));
            assertTrue(TenantPermissions.allows(role, "broadcast.manage"));
        }
    }

    @Test
    void operatorCanOperateButViewerCannotModifyResources() {
        assertTrue(TenantPermissions.allows(Role.OPERATOR, "camera.manage"));
        assertTrue(TenantPermissions.allows(Role.OPERATOR, "stream.manage"));
        assertTrue(TenantPermissions.allows(Role.OPERATOR, "broadcast.manage"));
        assertTrue(TenantPermissions.allows(Role.VIEWER, "camera.read"));
        assertTrue(TenantPermissions.allows(Role.VIEWER, "playback.view"));
        assertFalse(TenantPermissions.allows(Role.VIEWER, "camera.manage"));
        assertFalse(TenantPermissions.allows(Role.VIEWER, "stream.manage"));
        assertFalse(TenantPermissions.allows(Role.VIEWER, "broadcast.manage"));
    }
}
