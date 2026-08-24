package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeExecution;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Renders the exact question sent to the flow summary model. */
@Component
public class FlowSummaryPromptRenderer {

    private final ObjectMapper json;
    private final FlowTemplateEngine templates = new FlowTemplateEngine();

    public FlowSummaryPromptRenderer(ObjectMapper json) {
        this.json = json;
    }

    public String render(SkillFlowExecution flow, List<SkillFlowNodeExecution> nodes) {
        try {
            String allResults = json.writeValueAsString(nodes.stream().map(node -> Map.of(
                    "nodeKey", node.getNodeKey(),
                    "skill", node.getSkillName(),
                    "status", node.getStatus().name(),
                    "result", Objects.toString(node.getResultJson(), ""),
                    "error", Objects.toString(node.getErrorMessage(), ""))).toList());
            return templates.render(flow.getSummaryQuestionTemplateSnapshot(),
                    new FlowTemplateEngine.Context(Map.of(
                            "server_date", flow.getDataDate().toString(),
                            "original_question", flow.getOriginalQuestion(),
                            "flow_name", flow.getFlowName(),
                            "all_results", allResults)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("FlowSummaryPromptRenderFailed: " + e.getMessage(), e);
        }
    }
}
