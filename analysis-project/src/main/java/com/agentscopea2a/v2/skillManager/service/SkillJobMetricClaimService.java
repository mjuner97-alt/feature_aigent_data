package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.MetricReadinessStatus;
import com.agentscopea2a.v2.skillManager.entity.SkillJob;
import com.agentscopea2a.v2.skillManager.entity.SkillJobExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillMetricReadiness;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillJobMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class SkillJobMetricClaimService {
    private final SkillJobMapper jobs;
    private final SkillFlowMapper flows;

    public SkillJobMetricClaimService(SkillJobMapper jobs, SkillFlowMapper flows) {
        this.jobs = jobs;
        this.flows = flows;
    }

    @Transactional("gaussCustomerTransactionManager")
    public SkillJobExecution claim(SkillJob job, List<Long> requiredMetricIds, LocalDate dataDate) {
        if (requiredMetricIds.isEmpty()) return null;
        jobs.lockJobForMetric(job.getId());
        if (jobs.hasMetricExecutionOnDate(job.getId(), dataDate)) return null;
        for (Long metricId : requiredMetricIds) {
            SkillMetricReadiness readiness = flows.selectMetricReadiness(metricId, dataDate);
            if (readiness == null || readiness.getStatus() != MetricReadinessStatus.READY) return null;
        }
        SkillJobExecution execution = SkillJobExecution.builder()
                .jobId(job.getId()).triggerType("METRIC").status("PENDING")
                .mdFileWritten(false).mdFileExists(false).build();
        jobs.insertExecution(execution);
        return execution;
    }
}
