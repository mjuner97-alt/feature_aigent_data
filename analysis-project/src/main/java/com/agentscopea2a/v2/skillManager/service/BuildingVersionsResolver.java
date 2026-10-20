package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.hooks.VersionPlanKnowledgeHook;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Fixed database calendar selection. No user SQL or configurable date window. */
@Component
public class BuildingVersionsResolver {
    private final Supplier<VersionPlanKnowledgeHook> hook;

    @Autowired
    public BuildingVersionsResolver(ObjectProvider<VersionPlanKnowledgeHook> hook) {
        this.hook = hook::getIfAvailable;
    }

    public BuildingVersionsResolver(VersionPlanKnowledgeHook hook) {
        this.hook = () -> hook;
    }

    public void validate(Object marker, String type) {
        if (!(marker instanceof Map<?, ?> outer) || outer.size() != 1
                || !(outer.get("$dynamic") instanceof Map<?, ?> config)
                || !config.keySet().equals(Set.of("v", "kind"))
                || !(config.get("v") instanceof Integer version) || version != 1
                || !"building_versions".equals(config.get("kind"))) {
            throw new IllegalStateException("ScriptParamsInvalid: 在建版本为固定配置，不支持调整参数");
        }
        if (type == null || !Set.of("array", "string[]", "array<string>").contains(type.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("ScriptParamsInvalid: ParamRuleTypeMismatch: 在建版本仅支持字符串数组参数");
        }
    }

    public List<String> resolve() {
        try {
            VersionPlanKnowledgeHook source = hook.get();
            if (source == null) throw new IllegalStateException("common datasource unavailable");
            return new ArrayList<>(source.currentVersions());
        } catch (RuntimeException error) {
            // Do not expose driver messages, JDBC URLs or database credentials to preview callers.
            throw new IllegalStateException("BuildingVersionsUnavailable: 无法查询 common 数据库在建版本，请检查数据源和日历日期数据", error);
        }
    }
}
