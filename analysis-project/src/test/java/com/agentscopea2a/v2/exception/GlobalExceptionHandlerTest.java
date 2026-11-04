package com.agentscopea2a.v2.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    @Test
    void duplicateKeyReturnsConflictWithTraceIdInsteadOfRateLimit() {
        var request = new MockHttpServletRequest("POST", "/api/skills/save");
        var response = new GlobalExceptionHandler().handleDuplicateKey(
                new DuplicateKeyException("internal SQL detail"), request);
        assertEquals(409, response.getStatusCode().value());
        assertEquals("DatabaseKeyConflict", response.getBody().get("code"));
        assertFalse(response.getBody().get("errorId").isBlank());
        assertFalse(response.getBody().get("message").contains("internal SQL detail"));
    }

    @Test
    void rateLimitStillReturns429() {
        var response = new GlobalExceptionHandler().handleTooManyRequests(
                new TooManyRequestsException("queue full"));
        assertEquals(429, response.getStatusCode().value());
    }
}
