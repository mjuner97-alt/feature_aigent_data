package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.MetricReadinessStatus;
import com.agentscopea2a.v2.skillManager.entity.SkillDependencyMetric;
import com.agentscopea2a.v2.skillManager.entity.SkillMetricReadiness;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 依赖指标就绪登记服务:外部指标数据到达时(Skill Job 的 metric 触发入口)记录就绪状态。
 * <p>Skill Flow 的"指标门控"以此为准:流程执行必须等它依赖的全部指标登记 READY 后才会放行,
 * 就绪记录次日零点过期(每天重新登记)。</p>
 */
@Service
public class MetricReadinessService {

    private final SkillFlowMapper mapper;
    private final Clock clock;

    public MetricReadinessService(SkillFlowMapper mapper, @Qualifier("skillFlowClock") Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 登记某指标当日就绪(按 指标+数据日期 upsert),供等待中的流程执行解锁。 */
    @Transactional("gaussCustomerTransactionManager")
    public SkillMetricReadiness recordReady(SkillDependencyMetric metric) {
        if (metric == null || metric.getId() == null || metric.getCode() == null || metric.getCode().isBlank()) {
            throw new IllegalArgumentException("metric identity must not be blank");
        }
        LocalDateTime receivedAt = LocalDateTime.now(clock);
        LocalDate dataDate = receivedAt.toLocalDate();
        SkillMetricReadiness readiness = SkillMetricReadiness.builder()
                .metricId(metric.getId())
                .metricCode(metric.getCode())
                .dataDate(dataDate)
                .status(MetricReadinessStatus.READY)
                .readyAt(receivedAt)
                .expiresAt(dataDate.plusDays(1).atStartOfDay())
                .build();
        mapper.upsertMetricReadiness(readiness);
        return readiness;
    }
}
