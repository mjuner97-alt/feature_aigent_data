/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Replaces the 11 {@code @Value} injections in {@code HarnessA2aRunnerV2} constructor
 * with a single typed bean.
 *
 * <p>Bind target:
 * <ul>
 *   <li>{@code harness.a2a.workspace.path}</li>
 *   <li>{@code harness.a2a.model.instances.glm-main.{api-key,base-url,name}}</li>
 *   <li>{@code harness.a2a.model.instances.light-classifier.{api-key,base-url,name}}</li>
 *   <li>{@code harness.a2a.model.instances.fallback.{api-key,base-url,name}} (all optional)</li>
 * </ul>
 *
 * <p>Spring's relaxed binding maps kebab-case keys to camelCase fields, so
 * {@code glm-main} -> {@code glmMain}, {@code api-key} -> {@code apiKey}, etc.
 */
@ConfigurationProperties(prefix = "harness.a2a")
public class HarnessRunnerProperties {

    @NestedConfigurationProperty
    private Workspace workspace = new Workspace();

    @NestedConfigurationProperty
    private Model model = new Model();

    public Workspace getWorkspace() {
        return workspace;
    }

    public void setWorkspace(Workspace workspace) {
        this.workspace = workspace;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public static class Workspace {
        private String path = ".agentscope/workspace/harness-a2a";

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    public static class Model {
        @NestedConfigurationProperty
        private Instances instances = new Instances();

        public Instances getInstances() {
            return instances;
        }

        public void setInstances(Instances instances) {
            this.instances = instances;
        }
    }

    public static class Instances {
        @NestedConfigurationProperty
        private ModelInstance glmMain = new ModelInstance();

        @NestedConfigurationProperty
        private ModelInstance lightClassifier = new ModelInstance();

        @NestedConfigurationProperty
        private ModelInstance fallback = new ModelInstance();

        public ModelInstance getGlmMain() {
            return glmMain;
        }

        public void setGlmMain(ModelInstance glmMain) {
            this.glmMain = glmMain;
        }

        public ModelInstance getLightClassifier() {
            return lightClassifier;
        }

        public void setLightClassifier(ModelInstance lightClassifier) {
            this.lightClassifier = lightClassifier;
        }

        public ModelInstance getFallback() {
            return fallback;
        }

        public void setFallback(ModelInstance fallback) {
            this.fallback = fallback;
        }
    }

    public static class ModelInstance {
        private String apiKey = "";
        private String baseUrl = "";
        private String name = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
