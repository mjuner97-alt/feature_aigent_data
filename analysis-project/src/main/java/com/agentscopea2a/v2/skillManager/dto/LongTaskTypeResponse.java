package com.agentscopea2a.v2.skillManager.dto;

import java.time.LocalDateTime;

/** 返回给前端的流程类型对象，不直接暴露数据库实体。 */
public record LongTaskTypeResponse(
        Long id, String name, String description, Integer sortOrder, Boolean enabled,
        LocalDateTime createdAt, LocalDateTime updatedAt) {}



