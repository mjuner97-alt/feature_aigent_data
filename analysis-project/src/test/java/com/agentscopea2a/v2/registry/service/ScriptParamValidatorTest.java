package com.agentscopea2a.v2.registry.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.math.BigDecimal;

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

    @Test
    void acceptsGenericArrayOfStrings() {
        String schema = "[{\"name\":\"dept\",\"type\":\"array\",\"required\":true}]";
        assertDoesNotThrow(() -> new ScriptParamValidator().validate(
                schema, Map.of("dept", List.of("杭州开发二部"))));
    }

    @Test
    void acceptsFloatValues() {
        String schema = "[{\"name\":\"threshold\",\"type\":\"float\",\"required\":true}]";
        ScriptParamValidator validator = new ScriptParamValidator();
        assertDoesNotThrow(() -> validator.validate(schema, Map.of("threshold", 0.75d)));
        assertDoesNotThrow(() -> validator.validate(schema, Map.of("threshold", 1)));
        assertDoesNotThrow(() -> validator.validate(schema, Map.of("threshold", new BigDecimal("0.75"))));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(schema, Map.of("threshold", "0.75")));
    }
}
