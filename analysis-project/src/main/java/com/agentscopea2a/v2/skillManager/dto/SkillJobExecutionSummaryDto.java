package com.agentscopea2a.v2.skillManager.dto;
import com.agentscopea2a.v2.skillManager.entity.SkillJobExecution;
import java.time.LocalDateTime;
/** Scalar execution-center row; error text and reports are loaded on demand. */
public record SkillJobExecutionSummaryDto(Long id, Long jobId, String triggerType, String status,
 Boolean mdFileExists, boolean hasError, LocalDateTime createdAt, Integer queueAhead,
 String jobName, String skillName, String createdBy, String createdByName) {
 public static SkillJobExecutionSummaryDto of(SkillJobExecution e,Integer ahead,String name) {
  return new SkillJobExecutionSummaryDto(e.getId(),e.getJobId(),e.getTriggerType(),e.getStatus(),e.getMdFileExists(),
   e.getErrorMsg()!=null&&!e.getErrorMsg().isBlank(),e.getCreatedAt(),ahead,e.getJobName(),e.getSkillName(),e.getCreatedBy(),name);
 }
}
