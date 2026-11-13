package com.agentscopea2a.v2.pptanomaly;

import java.time.LocalDateTime;
import java.util.List;

public final class PptAnomalyModels {
    private PptAnomalyModels() {}

    public record Batch(long id, String name, String createdBy, LocalDateTime createdAt) {}
    public record Version(long id, long batchId, int versionNo, String versionName, String fileName,
                          String status, LocalDateTime uploadedAt) {}
    public record Finding(long id, int sequenceNo, int pageNo, String title, String chapter,
                          String sourceText, String anomalyKeyword, List<String> departments,
                          String ownerUserId, String status, String relation, boolean editable) {}
    public record Slide(long id, int pageNo, String title, String imageUrl, String renderStatus,
                        String recognitionType) {}
    public record FeedbackRequest(String judgement, List<String> adjustedDepartments,
                                  String explanation) {}
    public record DetailLink(long id, String aliasName, String targetUrl, String pptTitle,
                             String chapter, Integer pageNo, boolean enabled) {}
}
