package com.agentscopea2a.v2.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MockOrgServiceTest {

    @Test
    void getUserOrgs_returns_borrowed_user_both_groups() {
        MockOrgService svc = new MockOrgService();
        var orgs = svc.getUserOrgs("user_001");
        assertThat(orgs).hasSize(2);
        assertThat(orgs).extracting(MockOrgService.OrgRef::orgId)
                .contains("group_001", "group_002");
    }

    @Test
    void getApprover_returns_approver_for_known_org() {
        MockOrgService svc = new MockOrgService();
        assertThat(svc.getApprover("GROUP", "group_001")).isEqualTo("approver_001");
    }

    @Test
    void getApprover_returns_null_for_unknown_org() {
        MockOrgService svc = new MockOrgService();
        assertThat(svc.getApprover("GROUP", "unknown")).isNull();
    }

    @Test
    void isApprover_true_for_configured_approver() {
        MockOrgService svc = new MockOrgService();
        assertThat(svc.isApprover("approver_001")).isTrue();
        assertThat(svc.isApprover("user_001")).isFalse();
    }
}
