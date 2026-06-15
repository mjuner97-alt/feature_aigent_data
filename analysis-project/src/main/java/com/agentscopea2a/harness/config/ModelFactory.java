/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.harness.config;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.Models;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a {@link Model} from {@link ModelProperties} based on the configured provider.
 *
 * <p>Configuration lives entirely in {@code harness.a2a.model.*} — no environment-variable or
 * {@code .env} lookup.
 */
public final class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    private ModelFactory() {}

    public static Model createModel(ModelProperties props) {
        String provider = props.getProvider() == null ? "anthropic" : props.getProvider().toLowerCase();
        return switch (provider) {
            case "anthropic" -> buildAnthropic(props.getAnthropic());
            case "openai" -> buildOpenAI(props.getOpenai());
            case "glm" -> buildGlm(props.getGlm());
            default ->
                    throw new IllegalArgumentException(
                            "Unknown harness.a2a.model.provider='"
                                    + provider
                                    + "'. Supported: anthropic, openai, glm");
        };
    }

    private static Model buildAnthropic(ModelProperties.Anthropic a) {
        requireApiKey("anthropic", a.getApiKey());
        log.info(
                "Model provider=anthropic name={} baseUrl={} keyPrefix={}...",
                a.getName(),
                a.getBaseUrl(),
                a.getApiKey().substring(0, Math.min(12, a.getApiKey().length())));
        return Models.anthropic(a.getApiKey(), nullIfBlank(a.getBaseUrl()), a.getName());
    }

    private static Model buildOpenAI(ModelProperties.OpenAI o) {
        requireApiKey("openai", o.getApiKey());
        log.info(
                "Model provider=openai name={} baseUrl={} keyPrefix={}...",
                o.getName(),
                o.getBaseUrl(),
                o.getApiKey().substring(0, Math.min(12, o.getApiKey().length())));
        return Models.openAI(o.getApiKey(), nullIfBlank(o.getBaseUrl()), o.getName());
    }

    private static Model buildGlm(ModelProperties.Glm g) {
        requireApiKey("glm", g.getApiKey());
        log.info(
                "Model provider=glm name={} baseUrl={} keyPrefix={}...",
                g.getName(),
                g.getBaseUrl(),
                g.getApiKey().substring(0, Math.min(12, g.getApiKey().length())));
        return Models.glm(g.getApiKey(), nullIfBlank(g.getBaseUrl()), g.getName());
    }

    private static void requireApiKey(String provider, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "harness.a2a.model." + provider + ".api-key is blank — set it in application.properties");
        }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
