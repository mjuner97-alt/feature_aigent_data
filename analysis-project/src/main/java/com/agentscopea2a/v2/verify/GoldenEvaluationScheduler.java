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
package com.agentscopea2a.v2.verify;

import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Golden evaluation scheduler (V3.0 design §13.6). Triggers a Golden regression run on a cron
 * schedule so capability regression is caught after Agent/Prompt/Skill/Semantic-Contract upgrades
 * without manual invocation. Disabled by default ({@code golden.enabled=false}) - Golden runs the
 * full Data Agent per case, so it's expensive; enable + set cron in production.
 *
 * <p>{@code @EnableScheduling} is already on the main app class (shared with
 * {@link CalibrationRollbackWatcher}).
 */
@Component
public class GoldenEvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(GoldenEvaluationScheduler.class);

    private final GoldenEvaluationRunner goldenRunner;
    private final HarnessRunnerProperties props;

    public GoldenEvaluationScheduler(GoldenEvaluationRunner goldenRunner, HarnessRunnerProperties props) {
        this.goldenRunner = goldenRunner;
        this.props = props;
    }

    @Scheduled(cron = "${harness.a2a.verification.golden.cron:0 0 7 * * *}")
    public void scheduledEvaluation() {
        if (!props.getVerification().getGolden().isEnabled()) {
            return;
        }
        String label = "scheduled-" + LocalDate.now();
        String evalId = goldenRunner.startEvaluation(label, "v1", null, null, null, "scheduler");
        log.info("GoldenEvaluationScheduler: triggered scheduled evaluation {} (label={})", evalId, label);
    }
}
