package com.agentscopea2a.v2.skillManager.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowSchedulerGuardTest {
    @Test
    void allowsSchedulingWhenDatabaseMapperIsUnavailable() {
        FlowSchedulerGuard guard = new FlowSchedulerGuard(true, Optional.empty());

        assertTrue(guard.isAllowed());
    }
}
