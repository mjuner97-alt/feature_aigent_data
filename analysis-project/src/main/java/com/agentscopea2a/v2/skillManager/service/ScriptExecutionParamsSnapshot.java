package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeExecution;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;

/** Persist actual inputs before cache lookup or Python execution; retries reuse them. */
final class ScriptExecutionParamsSnapshot {
    private ScriptExecutionParamsSnapshot() {}

    static Map<String, Object> resolve(SkillFlowMapper mapper, ScriptParamRuleService rules,
                                       ObjectMapper json, SkillFlowNodeExecution node, LocalDate dataDate) {
        if (node.getResolvedScriptParamsJson() != null) {
            return read(json, node.getResolvedScriptParamsJson());
        }
        Map<String, Object> resolved = rules.resolve(node.getScriptId(), node.getScriptParamsJson(), dataDate);
        final String snapshot;
        try {
            snapshot = json.writeValueAsString(resolved);
        } catch (Exception e) {
            throw new IllegalStateException("ScriptParamsSnapshotInvalid: cannot serialize inputs", e);
        }
        // Verify persisted representation before allowing it to become the permanent snapshot.
        Map<String, Object> inputs = read(json, snapshot);
        if (node.getAttemptCount() == null || mapper.freezeNodeScriptParams(node.getId(), snapshot,
                node.getAttemptCount(), node.getLeaseOwner()) != 1) {
            throw new IllegalStateException("ScriptParamsSnapshotNotPersisted: node lease lost or snapshot already written");
        }
        node.setResolvedScriptParamsJson(snapshot);
        return inputs;
    }

    private static Map<String, Object> read(ObjectMapper json, String snapshot) {
        try {
            var value = json.readTree(snapshot);
            if (value == null || !value.isObject()) throw new IllegalArgumentException("inputs must be a JSON object");
            return json.convertValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // A corrupt saved snapshot must never silently fall back to recomputation.
            throw new IllegalStateException("ScriptParamsSnapshotInvalid: cannot read saved inputs", e);
        }
    }
}
