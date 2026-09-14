package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.dto.SkillReviewSnapshot;
import com.agentscopea2a.v2.skillManager.dto.SkillReviewSubmission;
import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.entity.SkillReviewAudit;
import com.agentscopea2a.v2.skillManager.entity.SkillReviewDimension;
import com.agentscopea2a.v2.skillManager.entity.SkillReviewRequest;
import com.agentscopea2a.v2.skillManager.mapper.SkillReviewMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/** Two-stage review state machine. Actor IDs are supplied by the controller header. */
@Service
public class SkillReviewService {
    private final SkillReviewMapper reviews;
    private final SkillMapper skills;
    private final SkillReviewSnapshotService snapshots;
    private final SkillFinalReviewerConfig finalReviewers;
    private final MockOrgService orgs;
    private final ObjectMapper json;

    public SkillReviewService(SkillReviewMapper reviews, SkillMapper skills,
                              SkillReviewSnapshotService snapshots, SkillFinalReviewerConfig finalReviewers,
                              MockOrgService orgs, ObjectMapper json) {
        this.reviews = reviews; this.skills = skills; this.snapshots = snapshots;
        this.finalReviewers = finalReviewers; this.orgs = orgs; this.json = json;
    }

    @Transactional("gaussCustomerTransactionManager")
    public Long submit(Long skillId, SkillReviewSubmission submission, String actor) {
        Skill skill = skills.selectById(skillId);
        if (skill == null || !actor.equals(skill.getOwnerUserId())) throw new IllegalStateException("SkillAccessDenied");
        if ("PERSONAL".equalsIgnoreCase(submission.visibility())) return null;
        if (reviews.selectActiveRequestBySkillId(skillId) != null) throw new IllegalStateException("ReviewAlreadyPending");
        SkillReviewSnapshotService.BuiltSnapshot built = snapshots.build(skill, submission, actor);
        for (SkillReviewSubmission.PublishTarget target : submission.publishTargets()) {
            if (orgs.getApprover(target.targetType(), target.targetId()) == null) throw new IllegalStateException("NoApproverConfigured");
        }
        String serialized;
        try { serialized = json.writeValueAsString(built.snapshot()); }
        catch (Exception e) { throw new IllegalStateException("SnapshotSerializationFailed", e); }
        LocalDateTime now = LocalDateTime.now();
        SkillReviewRequest request = SkillReviewRequest.builder().skillId(skillId).snapshotJson(serialized)
                .snapshotHash(built.snapshotHash()).status("DIMENSION_REVIEW").submitterUserId(actor).submittedAt(now).build();
        reviews.insertRequest(request);
        for (SkillReviewSubmission.PublishTarget target : submission.publishTargets()) {
            reviews.insertDimension(SkillReviewDimension.builder().requestId(request.getId()).targetType(target.targetType())
                    .targetId(target.targetId()).targetName(target.targetName()).status("PENDING").build());
        }
        audit(request.getId(), null, "REQUEST_SUBMIT", actor, null, "DIMENSION_REVIEW", null, request.getSnapshotHash());
        return request.getId();
    }

    @Transactional("gaussCustomerTransactionManager")
    public void approveDimension(Long requestId, Long dimensionId, String actor, String comment) {
        SkillReviewRequest r = require(requestId, "DIMENSION_REVIEW");
        SkillReviewDimension d = findDimension(requestId, dimensionId);
        if (!actor.equals(orgs.getApprover(d.getTargetType(), d.getTargetId()))) throw new IllegalStateException("NotApprover");
        requireUpdated(reviews.transitionDimensionStatus(dimensionId, "PENDING", "APPROVED", comment, LocalDateTime.now()));
        audit(requestId, dimensionId, "DIMENSION_APPROVE", actor, "PENDING", "APPROVED", comment, r.getSnapshotHash());
        List<SkillReviewDimension> all = reviews.selectDimensionsByRequestId(requestId);
        if (all.stream().allMatch(x -> "APPROVED".equals(x.getStatus()))) {
            requireUpdated(reviews.transitionRequestSimple(requestId, "DIMENSION_REVIEW", "FINAL_REVIEW"));
        }
    }

    @Transactional("gaussCustomerTransactionManager")
    public void rejectDimension(Long requestId, Long dimensionId, String actor, String comment) {
        if (comment == null || comment.isBlank()) throw new IllegalArgumentException("RejectionCommentRequired");
        SkillReviewRequest r = require(requestId, "DIMENSION_REVIEW");
        SkillReviewDimension d = findDimension(requestId, dimensionId);
        if (!actor.equals(orgs.getApprover(d.getTargetType(), d.getTargetId()))) throw new IllegalStateException("NotApprover");
        requireUpdated(reviews.transitionDimensionStatus(dimensionId, "PENDING", "REJECTED", comment, LocalDateTime.now()));
        reviews.cancelPendingDimensions(requestId, "PENDING", LocalDateTime.now());
        requireUpdated(reviews.transitionRequestSimple(requestId, "DIMENSION_REVIEW", "REJECTED"));
        audit(requestId, dimensionId, "DIMENSION_REJECT", actor, "PENDING", "REJECTED", comment, r.getSnapshotHash());
    }

    @Transactional("gaussCustomerTransactionManager")
    public void finalApprove(Long requestId, String actor, String comment) {
        SkillReviewRequest r = require(requestId, "FINAL_REVIEW");
        if (actor.equals(r.getSubmitterUserId()) || !finalReviewers.currentReviewerIds().contains(actor)) throw new IllegalStateException("FinalReviewForbidden");
        if (reviews.selectActualDimensionReviewerUserIds(requestId).contains(actor)) throw new IllegalStateException("FinalReviewForbidden");
        requireUpdated(reviews.transitionRequestStatus(requestId, "FINAL_REVIEW", "APPROVED", comment, LocalDateTime.now()));
        audit(requestId, null, "CONFIG_EFFECTIVE", actor, "FINAL_REVIEW", "APPROVED", comment, r.getSnapshotHash());
    }

    @Transactional("gaussCustomerTransactionManager")
    public void finalReject(Long requestId, String actor, String comment) {
        if (comment == null || comment.isBlank()) throw new IllegalArgumentException("RejectionCommentRequired");
        SkillReviewRequest r = require(requestId, "FINAL_REVIEW");
        if (!finalReviewers.currentReviewerIds().contains(actor) || actor.equals(r.getSubmitterUserId())) throw new IllegalStateException("FinalReviewForbidden");
        requireUpdated(reviews.transitionRequestStatus(requestId, "FINAL_REVIEW", "REJECTED", comment, LocalDateTime.now()));
        audit(requestId, null, "FINAL_REJECT", actor, "FINAL_REVIEW", "REJECTED", comment, r.getSnapshotHash());
    }

    @Transactional("gaussCustomerTransactionManager")
    public void withdraw(Long requestId, String actor) {
        SkillReviewRequest r = require(requestId, null);
        if (!actor.equals(r.getSubmitterUserId())) throw new IllegalStateException("SkillAccessDenied");
        requireUpdated(reviews.transitionRequestSimple(requestId, r.getStatus(), "WITHDRAWN"));
        reviews.cancelPendingDimensions(requestId, "PENDING", LocalDateTime.now());
        audit(requestId, null, "WITHDRAW", actor, r.getStatus(), "WITHDRAWN", null, r.getSnapshotHash());
    }

    public SkillReviewRequest get(Long id) { return reviews.selectRequestById(id); }
    public List<SkillReviewDimension> dimensions(Long id) { return reviews.selectDimensionsByRequestId(id); }

    private SkillReviewRequest require(Long id, String status) {
        SkillReviewRequest r = reviews.selectRequestById(id);
        if (r == null || (status != null && !status.equals(r.getStatus()))) throw new IllegalStateException("ReviewNotFoundOrStateConflict");
        return r;
    }
    private SkillReviewDimension findDimension(Long requestId, Long id) { return reviews.selectDimensionsByRequestId(requestId).stream().filter(x -> id.equals(x.getId())).findFirst().orElseThrow(() -> new IllegalArgumentException("DimensionNotFound")); }
    private void requireUpdated(int count) { if (count != 1) throw new IllegalStateException("ReviewStateConflict"); }
    private void audit(Long req, Long dim, String action, String actor, String prev, String next, String comment, String hash) { reviews.insertAudit(SkillReviewAudit.builder().requestId(req).dimensionId(dim).action(action).actorUserId(actor).previousStatus(prev).newStatus(next).comment(comment).snapshotHash(hash).build()); }
}
