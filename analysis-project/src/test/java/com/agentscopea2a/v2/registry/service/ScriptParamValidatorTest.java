package com.agentscopea2a.v2.registry.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScriptParamValidatorTest {
    private static final String SCHEMA = "["
            + "{\"name\":\"dept\",\"type\":\"string\",\"required\":true},"
            + "{\"name\":\"limit\",\"type\":\"int\",\"required\":false}"
            + "]";

    @Test
    void acceptsDeclaredValuesAndRejectsUnknownOrWrongType() {
        ScriptParamValidator validator = new ScriptParamValidator();

        assertDoesNotThrow(() -> validator.validate(SCHEMA, Map.of("dept", "研发", "limit", 3)));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(SCHEMA, Map.of("dept", "研发", "other", true)));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(SCHEMA, Map.of("dept", "研发", "limit", "3")));
    }

    @Test
    void rejectsMissingRequiredValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScriptParamValidator().validate(SCHEMA, Map.of()));
    }
}
