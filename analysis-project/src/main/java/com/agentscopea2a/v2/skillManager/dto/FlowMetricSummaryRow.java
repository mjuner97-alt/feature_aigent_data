package com.agentscopea2a.v2.skillManager.dto;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class FlowMetricSummaryRow {
    private Long flowId;
    private Long nodeId;
    private Boolean overrideConfigured;
    private String defaultMetricIds;
    private Long metricId;
}
