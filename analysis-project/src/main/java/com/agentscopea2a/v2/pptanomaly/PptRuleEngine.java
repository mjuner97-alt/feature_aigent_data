package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.config.datasource.GaussConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** 发布导入确认时已保存的版本快照，不再解析 PPT 或读取全局匹配配置。 */
@Service
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptRuleEngine {
    private final PptAnomalyMapper persistence;
    private final PptFeedbackNotifier notifier;

    public PptRuleEngine(PptAnomalyMapper persistence, PptFeedbackNotifier notifier) {
        this.persistence = persistence;
        this.notifier = notifier;
    }

    @Transactional(GaussConfig.TX_NAME)
    public int analyze(long versionId) {
        String status = persistence.lockVersion(versionId);
        if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        if ("ANALYZED".equals(status)) {
            notifier.notifyVersionAfterCommit(versionId);
            return persistence.findingCount(versionId);
        }
        if (!"IMPORT_CONFIRMED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先确认导入页面及经办人");
        }
        Long previous = persistence.previous(versionId);
        if (previous != null) persistence.reconcile(versionId, previous);
        if (persistence.carryFeedback(versionId) > 0) {
            persistence.markCarriedFeedbackComplete(versionId);
        }
        persistence.markAnalyzed(versionId);
        notifier.notifyVersionAfterCommit(versionId);
        return persistence.findingCount(versionId);
    }
}
