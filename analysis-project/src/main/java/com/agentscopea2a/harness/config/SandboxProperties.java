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
package com.agentscopea2a.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Sandbox / distributed-store toggles. Default to OFF so the example starts on a vanilla JVM
 * with no extra infra. Enable per profile (e.g. {@code application-sandbox.properties}) when
 * demoing the harness isolation / multi-replica stories.
 */
@Component
@ConfigurationProperties(prefix = "harness.a2a")
public class SandboxProperties {

    private Sandbox sandbox = new Sandbox();
    private Distributed distributed = new Distributed();
    private Artifacts artifacts = new Artifacts();
    private Skills skills = new Skills();
    private Memory memory = new Memory();

    public Sandbox getSandbox() {
        return sandbox;
    }

    public void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public Distributed getDistributed() {
        return distributed;
    }

    public void setDistributed(Distributed distributed) {
        this.distributed = distributed;
    }

    public Artifacts getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(Artifacts artifacts) {
        this.artifacts = artifacts;
    }

    public Skills getSkills() {
        return skills;
    }

    public void setSkills(Skills skills) {
        this.skills = skills;
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    /** Docker-sandbox isolation for filesystem + shell tools. */
    public static class Sandbox {
        private boolean enabled = false;

        /**
         * Image must be available on the host already. If the image is missing, sandbox
         * lifecycle will fail loudly when the first request hits.
         */
        private String image = "deepanalyze-vllm:latest";

        private String workspaceRoot = "/workspace";

        /** ISOLATION scope name — SESSION / USER / AGENT / GLOBAL. */
        private String isolationScope = "SESSION";

        /**
         * ISOLATION scope name for SUBAGENT sandboxes. Defaults to {@link #isolationScope} when
         * blank. Setting this to {@code USER} / {@code GLOBAL} converges subagents onto fewer
         * containers — cheaper at scale at the cost of shared mutable filesystem state.
         */
        private String subagentIsolationScope = "";

        private boolean mountSkills = true;
        private boolean mountMemory = true;
        private boolean mountArtifacts = true;

        /**
         * When non-blank, every Docker sandbox is wired to a pre-created, long-lived container
         * with this name. Combine with {@code isolationScope: GLOBAL} to make every user/session
         * converge on one container. Memory must move to MySQL for tenant safety.
         */
        private String sharedContainerName = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getWorkspaceRoot() {
            return workspaceRoot;
        }

        public void setWorkspaceRoot(String workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        public String getIsolationScope() {
            return isolationScope;
        }

        public void setIsolationScope(String isolationScope) {
            this.isolationScope = isolationScope;
        }

        public String getSubagentIsolationScope() {
            return subagentIsolationScope;
        }

        public void setSubagentIsolationScope(String subagentIsolationScope) {
            this.subagentIsolationScope = subagentIsolationScope;
        }

        public boolean isMountSkills() {
            return mountSkills;
        }

        public void setMountSkills(boolean mountSkills) {
            this.mountSkills = mountSkills;
        }

        public boolean isMountMemory() {
            return mountMemory;
        }

        public void setMountMemory(boolean mountMemory) {
            this.mountMemory = mountMemory;
        }

        public boolean isMountArtifacts() {
            return mountArtifacts;
        }

        public void setMountArtifacts(boolean mountArtifacts) {
            this.mountArtifacts = mountArtifacts;
        }

        public String getSharedContainerName() {
            return sharedContainerName;
        }

        public void setSharedContainerName(String sharedContainerName) {
            this.sharedContainerName = sharedContainerName == null ? "" : sharedContainerName.trim();
        }
    }

    /** Remote-filesystem + Redis execution-guard multi-replica mode. */
    public static class Distributed {
        private boolean enabled = false;
        private String redisHost = "127.0.0.1";
        private int redisPort = 6379;
        private String redisPassword = "";

        /** ISOLATION scope name — SESSION / USER / AGENT / GLOBAL. */
        private String isolationScope = "USER";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRedisHost() {
            return redisHost;
        }

        public void setRedisHost(String redisHost) {
            this.redisHost = redisHost;
        }

        public int getRedisPort() {
            return redisPort;
        }

        public void setRedisPort(int redisPort) {
            this.redisPort = redisPort;
        }

        public String getRedisPassword() {
            return redisPassword;
        }

        public void setRedisPassword(String redisPassword) {
            this.redisPassword = redisPassword;
        }

        public String getIsolationScope() {
            return isolationScope;
        }

        public void setIsolationScope(String isolationScope) {
            this.isolationScope = isolationScope;
        }
    }

    /** CSV artifact persistence. local JVM disk by default; remote SSH when {@code remote.enabled=true}. */
    public static class Artifacts {
        private Remote remote = new Remote();

        public Remote getRemote() {
            return remote;
        }

        public void setRemote(Remote remote) {
            this.remote = remote;
        }

        public static class Remote {
            private boolean enabled = false;
            private String sshTarget = "";
            private String remoteRoot = "";
            private java.util.List<String> sshOptions = new java.util.ArrayList<>();
            private long timeoutSeconds = 30;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getSshTarget() {
                return sshTarget;
            }

            public void setSshTarget(String sshTarget) {
                this.sshTarget = sshTarget;
            }

            public String getRemoteRoot() {
                return remoteRoot;
            }

            public void setRemoteRoot(String remoteRoot) {
                this.remoteRoot = remoteRoot;
            }

            public java.util.List<String> getSshOptions() {
                return sshOptions;
            }

            public void setSshOptions(java.util.List<String> sshOptions) {
                this.sshOptions = sshOptions;
            }

            public long getTimeoutSeconds() {
                return timeoutSeconds;
            }

            public void setTimeoutSeconds(long timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
            }
        }
    }

    /** Skills directory mirroring for remote-Docker mode. */
    public static class Skills {
        private Remote remote = new Remote();

        public Remote getRemote() {
            return remote;
        }

        public void setRemote(Remote remote) {
            this.remote = remote;
        }

        public static class Remote {
            private boolean enabled = false;
            private String sshTarget = "";
            private String remoteRoot = "";
            private java.util.List<String> sshOptions = new java.util.ArrayList<>();
            private long timeoutSeconds = 30;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getSshTarget() {
                return sshTarget;
            }

            public void setSshTarget(String sshTarget) {
                this.sshTarget = sshTarget;
            }

            public String getRemoteRoot() {
                return remoteRoot;
            }

            public void setRemoteRoot(String remoteRoot) {
                this.remoteRoot = remoteRoot;
            }

            public java.util.List<String> getSshOptions() {
                return sshOptions;
            }

            public void setSshOptions(java.util.List<String> sshOptions) {
                this.sshOptions = sshOptions;
            }

            public long getTimeoutSeconds() {
                return timeoutSeconds;
            }

            public void setTimeoutSeconds(long timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
            }
        }
    }

    /** Memory directory mirroring for remote-Docker mode. */
    public static class Memory {
        private Remote remote = new Remote();

        public Remote getRemote() {
            return remote;
        }

        public void setRemote(Remote remote) {
            this.remote = remote;
        }

        public static class Remote {
            private boolean enabled = false;
            private String sshTarget = "";
            private String remoteRoot = "";
            private java.util.List<String> sshOptions = new java.util.ArrayList<>();
            private long timeoutSeconds = 30;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getSshTarget() {
                return sshTarget;
            }

            public void setSshTarget(String sshTarget) {
                this.sshTarget = sshTarget;
            }

            public String getRemoteRoot() {
                return remoteRoot;
            }

            public void setRemoteRoot(String remoteRoot) {
                this.remoteRoot = remoteRoot;
            }

            public java.util.List<String> getSshOptions() {
                return sshOptions;
            }

            public void setSshOptions(java.util.List<String> sshOptions) {
                this.sshOptions = sshOptions;
            }

            public long getTimeoutSeconds() {
                return timeoutSeconds;
            }

            public void setTimeoutSeconds(long timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
            }
        }
    }
}
