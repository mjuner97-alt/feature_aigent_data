package com.agentscopea2a.v2.auth;

import com.agentscopea2a.v2.auth.service.AdminRoleService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminRoleServiceTest {

    @Test
    void parsesUserIdPasswordPairs() {
        AdminRoleService service = new AdminRoleService("admin:secret1, ops:pass two", false);
        assertTrue(service.isAdminUserId("admin"));
        assertTrue(service.isAdminUserId("OPS"));
        assertTrue(service.matchesPassword("admin", "secret1"));
        assertTrue(service.matchesPassword("ops ", "pass two"));
    }

    @Test
    void blankOrUnknownUserIsNotAdmin() {
        AdminRoleService service = new AdminRoleService("admin:secret1", false);
        assertFalse(service.isAdminUserId(null));
        assertFalse(service.isAdminUserId("  "));
        assertFalse(service.isAdminUserId("alice"));
        assertFalse(service.matchesPassword("alice", "secret1"));
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        AdminRoleService service = new AdminRoleService("admin:secret1", false);
        assertFalse(service.matchesPassword("admin", null));
        assertFalse(service.matchesPassword("admin", ""));
        assertFalse(service.matchesPassword("admin", "wrong"));
        assertFalse(service.matchesPassword("ADMIN", "secret1".toUpperCase()));
    }

    @Test
    void emptyConfigMeansNoAdmin() {
        AdminRoleService service = new AdminRoleService("", false);
        assertFalse(service.isAdminUserId("admin"));
        assertFalse(service.matchesPassword("admin", "anything"));
    }

    @Test
    void malformedPairsAreSkipped() {
        AdminRoleService service = new AdminRoleService("noSeparator, :nouser, admin:, trailing:", false);
        assertFalse(service.isAdminUserId("noSeparator"));
        assertFalse(service.isAdminUserId("nouser"));
        assertFalse(service.isAdminUserId("admin"));
        assertFalse(service.isAdminUserId("trailing"));
    }

    @Test
    void passwordMayContainColon() {
        AdminRoleService service = new AdminRoleService("admin:pa:ss:word", false);
        assertTrue(service.matchesPassword("admin", "pa:ss:word"));
    }

    @Test
    void canEditConfigAllowsEveryoneInTestMode() {
        AdminRoleService service = new AdminRoleService("admin:secret1", false);
        assertFalse(service.isProductionMode());
        assertTrue(service.canEditConfig("admin"));
        assertTrue(service.canEditConfig("alice"));
        assertTrue(service.canEditConfig(null));
    }

    @Test
    void canEditConfigRestrictsToAdminInProductionMode() {
        AdminRoleService service = new AdminRoleService("admin:secret1", true);
        assertTrue(service.isProductionMode());
        assertTrue(service.canEditConfig("ADMIN"));
        assertFalse(service.canEditConfig("alice"));
        assertFalse(service.canEditConfig(null));
    }
}
