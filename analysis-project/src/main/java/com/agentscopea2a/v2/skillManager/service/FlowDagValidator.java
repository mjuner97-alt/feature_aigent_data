package com.agentscopea2a.v2.skillManager.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill Flow 依赖图(DAG)校验器:启用流程前检查节点编排是否合法。
 * <p>校验内容:节点不为空、nodeKey 非空且不重复、依赖的前置节点存在、无自依赖、依赖图无环。</p>
 */
public class FlowDagValidator {

    /** 参与校验的节点定义:节点 key + 它依赖的前置节点 key 列表。 */
    public record NodeDefinition(String nodeKey, List<String> dependsOn) {
        public NodeDefinition {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }

    /** 校验结果:valid=false 时 errors 列出所有问题(不通过则流程不允许启用)。 */
    public record ValidationResult(boolean valid, List<String> errors) {
        public ValidationResult {
            errors = List.copyOf(errors);
        }
    }

    /** 校验一组节点编排,返回全部错误(而不是遇到第一个就停)。 */
    public ValidationResult validate(Collection<NodeDefinition> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return invalid("flow must define at least one node");
        }

        List<NodeDefinition> definitions = List.copyOf(nodes);
        Set<String> keys = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        for (NodeDefinition node : definitions) {
            if (node == null || node.nodeKey() == null || node.nodeKey().isBlank()) {
                errors.add("node key must not be blank");
            } else if (!keys.add(node.nodeKey())) {
                errors.add("duplicate node key: " + node.nodeKey());
            }
        }

        // 构建每个节点的前置依赖集合,同时校验自依赖 / 未知前置节点
        Map<String, Set<String>> predecessors = new HashMap<>();
        for (NodeDefinition node : definitions) {
            if (node == null || node.nodeKey() == null || node.nodeKey().isBlank()) {
                continue;
            }
            Set<String> nodePredecessors = new LinkedHashSet<>();
            predecessors.put(node.nodeKey(), nodePredecessors);
            for (String predecessor : node.dependsOn()) {
                if (node.nodeKey().equals(predecessor)) {
                    errors.add("node " + node.nodeKey() + " has a self dependency");
                } else if (!keys.contains(predecessor)) {
                    errors.add("node " + node.nodeKey() + " has unknown predecessor: " + predecessor);
                } else {
                    nodePredecessors.add(predecessor);
                }
            }
        }

        // 其他结构性错误都没有时才做环检测(环检测的前提是依赖都指向存在的节点)
        if (errors.isEmpty() && containsCycle(predecessors)) {
            errors.add("flow dependency graph contains a cycle");
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    /** Kahn 拓扑排序判环:无法遍历完全部节点即存在环。 */
    private boolean containsCycle(Map<String, Set<String>> predecessors) {
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, Set<String>> successors = new HashMap<>();
        predecessors.forEach((node, nodePredecessors) -> {
            incoming.put(node, nodePredecessors.size());
            for (String predecessor : nodePredecessors) {
                successors.computeIfAbsent(predecessor, ignored -> new HashSet<>()).add(node);
            }
        });

        ArrayDeque<String> roots = new ArrayDeque<>();
        incoming.forEach((node, count) -> {
            if (count == 0) {
                roots.add(node);
            }
        });
        int visited = 0;
        while (!roots.isEmpty()) {
            String node = roots.removeFirst();
            visited++;
            for (String successor : successors.getOrDefault(node, Set.of())) {
                int remaining = incoming.merge(successor, -1, Integer::sum);
                if (remaining == 0) {
                    roots.addLast(successor);
                }
            }
        }
        return visited != predecessors.size();
    }

    private ValidationResult invalid(String error) {
        return new ValidationResult(false, List.of(error));
    }
}
