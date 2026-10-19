package com.agentscopea2a.v2.skillManager.dto;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class ReportProcessSummaryDto {
    private Long id;
    private String code;
    private String name;
    private String description;
    private Boolean enabled;
    private Long copiedFromId;
    private String copiedFromName;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int nodeCount;
}
