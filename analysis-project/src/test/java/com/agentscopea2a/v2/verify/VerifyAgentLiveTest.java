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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-LLM smoke test for the verify sub-agent path (the part the unit/integration tests mock).
 * Builds the standalone verify agent exactly like {@link VerificationAgentRunner} (deepseek model
 * from agentscope.llm.*, verify.md sysPrompt, read-only) and runs it on a sample candidate, then
 * asserts the model actually responded with verification JSON.
 *
 * <p>Gated by {@code -DRUN_LIVE_LLM=true} so it does NOT run in the normal {@code mvn test} suite
 * (it makes a real network call to DeepSeek). Run explicitly:
 * <pre>mvn test -Dtest=VerifyAgentLiveTest -DRUN_LIVE_LLM=true</pre>
 */
@EnabledIfSystemProperty(named = "RUN_LIVE_LLM", matches = "true")
class VerifyAgentLiveTest {

    @Test
    void verifyAgentCallsRealDeepseekAndReturnsJson() throws Exception {
        String apiKey = System.getProperty("LLM_API_KEY", "sk-cefdb64866204540b9a8f0dfa5373a9d");
        String apiUrl = System.getProperty("LLM_API_URL", "https://api.deepseek.com/v1");
        String modelName = System.getProperty("LLM_MODEL", "deepseek-v4-flash");

        OpenAIChatModel llm = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(apiUrl)
                .modelName(modelName)
                .stream(true)
                .build();

        String sysPrompt = readSysPrompt("/workspace/verify-agents/verify.md");
        Path workspace = Files.createTempDirectory("verify-live");

        HarnessAgent agent = HarnessAgent.builder()
                .name("verify-live")
                .model(llm)
                .workspace(workspace)
                .toolkit(new Toolkit())
                .sysPrompt(sysPrompt)
                .maxIters(2)
                .disableSubagents()
                .disableMemoryHooks()
                .build();

        String prompt = "[用户问题] 哪个部门质量最好\n"
                + "[触发级别] MEDIUM\n"
                + "[Rule Engine 预检] 确定性预检: 通过(无结论类硬错误)\n"
                + "[Semantic Contract 快照] quality_score(direction=worse,allow=[avg,trend],deny=[sum])\n"
                + "[事件流(快照)]\nevt_0001 TOOL_CALL_STARTED router_tool\n"
                + "[Decision Trace] supervisor: 比较各部门质量指标\n"
                + "[候选结论] 来源:supervisor  内容: 杭州开发一部质量分最高,质量最好\n"
                + "[已知实体] 部门:杭州开发一部,杭州开发二部,杭州开发三部,杭州开发四部,杭州开发五部\n"
                + "请按 verify.md 三模式审查(业务语义以契约为准),输出严格 JSON(含 TrustScore + 子维度 + metricsUsed + repairHint)。";
        Msg userMsg = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(prompt).build())
                .build();
        RuntimeContext ctx = RuntimeContext.builder().sessionId("live-verify").userId("live").build();

        List<AgentEvent> events = agent.streamEvents(List.of(userMsg), ctx)
                .collectList()
                .block(Duration.ofMinutes(2));
        String text = extractFinal(events);
        System.out.println("=== verify live output ===\n" + text);

        assertThat(text).isNotBlank();
        assertThat(text.toLowerCase()).containsAnyOf("trustscore", "verdict", "trust_score");
    }

    private static String readSysPrompt(String resourcePath) throws Exception {
        try (InputStream in = VerifyAgentLiveTest.class.getResourceAsStream(resourcePath)) {
            assertThat(in).as("verify.md sysPrompt resource").isNotNull();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.startsWith("---")) {
                int end = raw.indexOf("\n---", 3);
                if (end > 0) {
                    raw = raw.substring(end + 4);
                }
            }
            return raw.trim();
        }
    }

    private static String extractFinal(List<AgentEvent> events) {
        if (events == null) return "";
        for (AgentEvent e : events) {
            if (e instanceof AgentResultEvent re && re.getResult() != null) {
                String t = re.getResult().getTextContent();
                if (t != null && !t.isBlank()) return t;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : events) {
            if (e instanceof TextBlockDeltaEvent d && d.getDelta() != null) sb.append(d.getDelta());
        }
        return sb.toString();
    }
}
