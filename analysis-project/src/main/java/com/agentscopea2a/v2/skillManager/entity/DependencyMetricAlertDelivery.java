package com.agentscopea2a.v2.skillManager.entity;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class DependencyMetricAlertDelivery {
    private Long id;
    private LocalDate alertDate;
    private String status;
    private String recipientSummary;
    private String content;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
}
