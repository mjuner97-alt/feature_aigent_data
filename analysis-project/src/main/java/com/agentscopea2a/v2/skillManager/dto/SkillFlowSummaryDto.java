package com.agentscopea2a.v2.skillManager.dto;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class SkillFlowSummaryDto {
    private Long id;
    private String code;
    private String name;
    private String description;
    private Boolean enabled;
    private Boolean chatPublic;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int nodeCount;
    private int metricCount;
    private boolean canExecute;
}
