package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportProcess {
    private Long id;
    private String code;
    private String name;
    private String description;
    private String nodesJson;
    private String reportOutlineJson;
    private Boolean enabled;
    private Long copiedFromId;
    private String copiedFromName;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}

