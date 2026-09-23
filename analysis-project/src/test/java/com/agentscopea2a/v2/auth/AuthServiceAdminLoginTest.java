package com.agentscopea2a.v2.auth;

import com.agentscopea2a.v2.auth.dto.LoginRequest;
import com.agentscopea2a.v2.auth.dto.LoginResponse;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import com.agentscopea2a.v2.auth.service.AdminRoleService;
import com.agentscopea2a.v2.auth.service.AuthService;
import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceAdminLoginTest {

    private final DeveloperPlPersonInfoMapper mapper = mock(DeveloperPlPersonInfoMapper.class);
    private final AuthService service = new AuthService(mapper, new AdminRoleService("admin:secret1", false));

    @Test
    void adminLoginWithCorrectPasswordSkipsPersonInfoTable() {
        LoginResponse response = service.login(new LoginRequest("admin", "secret1"));
        assertTrue(response.isAdmin());
        assertEquals("admin", response.getUserId());
        assertEquals("admin", response.getName());
        verifyNoInteractions(mapper);
    }

    @Test
    void adminLoginWithWrongPasswordFails() {
        assertThrows(IllegalArgumentException.class,
                () -> service.login(new LoginRequest("admin", "wrong")));
        assertThrows(IllegalArgumentException.class,
                () -> service.login(new LoginRequest("admin", null)));
        verifyNoInteractions(mapper);
    }

    @Test
    void normalUserLoginUnchangedAndNotAdmin() {
        DeveloperPlPersonInfo row = new DeveloperPlPersonInfo();
        row.setUserId("alice");
        row.setName("Alice");
        when(mapper.countByUserId("alice")).thenReturn(1);
        when(mapper.selectByUserId("alice")).thenReturn(List.of(row));

        LoginResponse response = service.login(new LoginRequest("alice"));
        assertFalse(response.isAdmin());
        assertEquals("Alice", response.getName());
    }

    @Test
    void unknownUserStillRejected() {
        when(mapper.countByUserId("bob")).thenReturn(0);
        assertThrows(IllegalArgumentException.class,
                () -> service.login(new LoginRequest("bob")));
    }
}
