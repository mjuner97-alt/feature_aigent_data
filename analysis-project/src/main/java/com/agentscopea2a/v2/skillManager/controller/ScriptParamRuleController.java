package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.entity.ScriptParamRule;
import com.agentscopea2a.v2.skillManager.mapper.ScriptParamRuleMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供长任务脚本参数的启用预设规则，用户只能从规则列表中选择。 */
@RestController
@CrossOrigin(origins = "*", maxAge = 3600)
@ConditionalOnProperty(prefix = "harness.a2a.skill-flow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ScriptParamRuleController {

    private final ScriptParamRuleMapper mapper;

    public ScriptParamRuleController(ScriptParamRuleMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/api/script-param-rules")
    public List<ScriptParamRule> listEnabled() {
        return mapper.selectEnabledRules();
    }
}
