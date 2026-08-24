package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/**
 * Skill Flow 完整性校验结果:valid=false 时 errors 里逐条列出校验未通过的原因。
 * 供编辑器在启用前调用 /api/skill-flows/{id}/validate 预检。
 */
public record FlowValidationDto(boolean valid, List<String> errors) {

    public FlowValidationDto {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
