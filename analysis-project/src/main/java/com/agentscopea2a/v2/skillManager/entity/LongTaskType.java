package com.agentscopea2a.v2.skillManager.entity;

import lombok.*;
import java.time.LocalDateTime;

/** skill_flow_type 表对应实体，表示长任务流程的分类。 */
@Data @Builder @NoArgsConstructor
public class LongTaskType {
    /** 类型主键。 */ private Long id;
    /** 类型名称。 */ private String name;
    /** 类型说明。 */ private String description;
    /** 展示排序，越小越靠前。 */ private Integer sortOrder;
    /** 是否允许前端继续选择该类型。 */ private Boolean enabled;
    /** 创建时间。 */ private LocalDateTime createdAt;
    /** 最近更新时间。 */ private LocalDateTime updatedAt;

    /** 对实体名称和排序值执行基础校验。 */
    public void normalizeAndValidate() {
        name = name == null ? null : name.trim();
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("LongTaskTypeNameRequired");
        if (sortOrder == null || sortOrder < 0) throw new IllegalArgumentException("LongTaskTypeSortOrderInvalid");
    }

    /** 创建实体并在进入持久化层前完成字段规范化。 */
    public LongTaskType(Long id, String name, String description, Integer sortOrder, Boolean enabled,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id; this.name = name == null ? null : name.trim(); this.description = description;
        this.sortOrder = sortOrder; this.enabled = enabled; this.createdAt = createdAt; this.updatedAt = updatedAt;
        normalizeAndValidate();
    }
}



