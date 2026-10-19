package com.agentscopea2a.v2.skillManager.dto;
import com.agentscopea2a.v2.skillManager.entity.SkillJob;
import java.time.LocalDateTime;
/** Scalar management row; templates are fetched through the detail API. */
public record SkillJobSummaryDto(Long id, String name, Long skillId, String skillName, Boolean enabled,
 Long metricId, String metricCode, String metricName, String createdBy, String createdByName,
 LocalDateTime createdAt, LocalDateTime updatedAt) {
 public static SkillJobSummaryDto of(SkillJob j, String name) {
  return new SkillJobSummaryDto(j.getId(),j.getName(),j.getSkillId(),j.getSkillName(),j.getEnabled(),j.getMetricId(),
   j.getMetricCode(),j.getMetricName(),j.getCreatedBy(),name,j.getCreatedAt(),j.getUpdatedAt());
 }
}
