package com.agentscopea2a.v2.skillManager.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;

@Data
public class NotificationLinkClickFilterRequest {
    private String userId;
    private String taskName;
    private Long executionId;
    private String environment;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate executionFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate executionTo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate accessFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate accessTo;
    private String source;
    private String eventType;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;
    private String granularity;
}
