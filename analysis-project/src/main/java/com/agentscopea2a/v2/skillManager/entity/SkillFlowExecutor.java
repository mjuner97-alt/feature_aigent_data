package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 长任务可执行人关系表(skill_flow_executor)实体。
 * 保存创建人指定的额外手动执行用户;创建人天然可执行,不入表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFlowExecutor {

    /** 可执行人关系主键。 */
    private Long id;

    /** 关联 skill_flow.id。 */
    private Long flowId;

    /** 可执行人用户 ID,需通过人员服务校验。 */
    private String userId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
