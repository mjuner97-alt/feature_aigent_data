package com.agentscopea2a.v2.skillManager.entity;

import lombok.*;
import java.time.LocalDateTime;

/** skill_flow_tag 表对应实体，表示流程可绑定的标签。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LongTaskTag {
    /** 标签主键。 */ private Long id;
    /** 标签名称。 */ private String name;
    /** 创建时间。 */ private LocalDateTime createdAt;
    /** 最近更新时间。 */ private LocalDateTime updatedAt;
}



