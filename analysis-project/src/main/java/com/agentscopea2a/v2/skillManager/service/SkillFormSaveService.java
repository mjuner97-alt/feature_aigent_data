package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.Skill;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** A form save and all its publications share one transaction. */
@Service
public class SkillFormSaveService {
    private final SkillManageService skills;
    private final MockOrgService orgs;
    public SkillFormSaveService(SkillManageService skills, MockOrgService orgs) {
        this.skills = skills;
        this.orgs = orgs;
    }
    public record PublishTarget(String targetType, String targetId, String targetName) {}
    public record Grant(String grantType, String targetId) {}
    public record SaveRequest(Long id, Skill skill, List<PublishTarget> targets,
                              List<Long> fileIds, List<Grant> grants) {}

    @Transactional(value = "gaussCustomerTransactionManager", rollbackFor = Exception.class)
    public Skill save(SaveRequest request, String userId) {
        if (request.skill() == null) throw new IllegalArgumentException("SkillRequired");
        for (PublishTarget target : orEmpty(request.targets())) {
            if (target == null || target.targetType() == null || target.targetId() == null
                    || target.targetId().isBlank()) throw new IllegalArgumentException("InvalidPublishTarget");
            String approver = orgs.getApprover(target.targetType(), target.targetId());
            if (approver == null || approver.isBlank()) {
                throw new IllegalStateException("NoApproverConfigured: " + target.targetType() + ":" + target.targetId());
            }
        }
        Skill saved = request.id() == null ? skills.create(request.skill(), userId)
                : skills.update(request.id(), request.skill(), userId);
        for (Long fileId : orEmpty(request.fileIds())) {
            skills.addFileReference(saved.getId(), fileId, "ATTACHMENT", userId);
        }
        for (Grant grant : orEmpty(request.grants())) {
            skills.addGrant(saved.getId(), grant.grantType(), grant.targetId(), userId);
        }
        for (PublishTarget target : orEmpty(request.targets())) {
            skills.submitPublish(saved.getId(), target.targetType(), target.targetId(), target.targetName(), userId);
        }
        return saved;
    }
    private static <T> List<T> orEmpty(List<T> values) { return values == null ? List.of() : values; }
}
