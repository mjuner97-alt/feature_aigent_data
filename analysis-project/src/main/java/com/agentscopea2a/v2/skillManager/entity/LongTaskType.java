package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor
public class LongTaskType {
    private Long id;
    private String name;
    private String description;
    private Integer sortOrder;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    public void normalizeAndValidate() {
        name = name == null ? null : name.trim();
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("LongTaskTypeNameRequired");
        if (sortOrder == null || sortOrder < 0) throw new IllegalArgumentException("LongTaskTypeSortOrderInvalid");
    }
    public LongTaskType(Long id, String name, String description, Integer sortOrder, Boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id=id; this.name=name == null ? null : name.trim(); this.description=description; this.sortOrder=sortOrder; this.enabled=enabled; this.createdAt=createdAt; this.updatedAt=updatedAt;
        normalizeAndValidate();
    }
}

