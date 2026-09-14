package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.dto.SkillReviewSnapshot;
import com.agentscopea2a.v2.skillManager.dto.SkillReviewSubmission;
import com.agentscopea2a.v2.skillManager.dto.SkillReviewSubmission.AttachmentReference;
import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.entity.SkillApprover;
import com.agentscopea2a.v2.skillManager.entity.SkillFile;
import com.agentscopea2a.v2.skillManager.mapper.SkillApproverMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import com.agentscopea2a.v2.toolrouting.ToolRoutingMetadataRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 技能评审快照服务 - 校验并冻结一份完整的候选配置(不写入任何正式/评审记录行)。
 *
 * <p>负责:提交人校验(仅 owner 可提交)、可见性/发布目标参数校验、发布目标必须有有效管理员、
 * 工具绑定校验、附件归属校验与内容指纹(大小 + SHA-256)计算、
 * 以及按规范序列化 JSON 并计算快照哈希。</p>
 */
@Service
public class SkillReviewSnapshotService {
    /** 固定的序列化配置,保证同一内容序列化结果稳定(不可更改,否则影响哈希一致性)。 */
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .build();
    private static final Set<String> VISIBILITIES = Set.of("PERSONAL", "PRIVATE", "PUBLIC");
    private final SkillMapper skills;
    private final SkillFileService files;
    private final ToolRoutingMetadataRepository tools;
    private final SkillApproverMapper approvers;

    public SkillReviewSnapshotService(SkillMapper skills, SkillFileService files,
                                      ToolRoutingMetadataRepository tools, SkillApproverMapper approvers) {
        this.skills = skills;
        this.files = files;
        this.tools = tools;
        this.approvers = approvers;
    }

    /**
     * 校验并冻结完整候选配置,返回规范 JSON 与快照哈希。
     *
     * @param current        当前正式的技能记录(必须已存在且是提交人自己的)
     * @param submission     待评审的候选配置
     * @param submitterUserId 提交人用户 ID
     * @return 冻结的快照(含原始对象、规范 JSON、SHA-256 哈希)
     * @throws IllegalArgumentException 校验不通过(非 owner 提交/非法可见性/目标无管理员/工具无效/附件缺失等)
     */
    public BuiltSnapshot build(Skill current, SkillReviewSubmission submission, String submitterUserId) {
        if (current == null || current.getId() == null || current.getId() <= 0) {
            throw new IllegalArgumentException("Invalid formal Skill");
        }
        if (submitterUserId == null || submitterUserId.isBlank()
                || !submitterUserId.strip().equals(current.getOwnerUserId())) {
            throw new IllegalArgumentException("Only the Skill owner may submit a configuration");
        }
        if (submission == null || submission.visibility() == null || !VISIBILITIES.contains(submission.visibility())) {
            throw new IllegalArgumentException("Visibility must be PERSONAL, PRIVATE or PUBLIC");
        }
        validateTargets(submission);
        for (String toolId : submission.toolIds()) {
            if (tools.findEnabledByToolId(toolId).isEmpty()) {
                throw new IllegalArgumentException("Unknown or disabled tool binding");
            }
        }
        List<SkillReviewSnapshot.Attachment> attachments = new ArrayList<>();
        for (AttachmentReference reference : submission.attachments()) {
            attachments.add(resolveAttachment(reference, current.getOwnerUserId()));
        }
        var snapshot = new SkillReviewSnapshot(current.getId(), current.getOwnerUserId(), current.getRetrievalName(),
                submission, attachments);
        try {
            String json = CANONICAL_JSON.writeValueAsString(snapshot);
            String hash = HexFormat.of().formatHex(sha256().digest(json.getBytes(StandardCharsets.UTF_8)));
            return new BuiltSnapshot(snapshot, json, hash);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize review snapshot", e);
        }
    }

    private void validateTargets(SkillReviewSubmission submission) {
        if ("PERSONAL".equals(submission.visibility())) return;
        if (submission.publishTargets().isEmpty()) {
            throw new IllegalArgumentException("Non-personal Skills require at least one publish target");
        }
        for (var target : submission.publishTargets()) {
            // Match the existing organization resolver; display names are never authorization keys.
            String scope = "COMPANY".equals(target.targetType()) ? "杭研" : target.targetId();
            List<SkillApprover> candidates = approvers.selectByScope(target.targetType(), scope);
            if (candidates == null || candidates.stream().filter(Objects::nonNull).noneMatch(candidate ->
                    "ACTIVE".equals(candidate.getStatus()) && candidate.getUserId() != null
                            && !candidate.getUserId().isBlank())) {
                throw new IllegalArgumentException("A publish target has no active administrator");
            }
        }
    }

    private SkillReviewSnapshot.Attachment resolveAttachment(AttachmentReference reference, String owner) {
        SkillFile file = skills.selectFileById(reference.fileId());
        if (file == null || !owner.equals(file.getUserId()) || file.getStoragePath() == null
                || file.getStoragePath().isBlank()) {
            throw new IllegalArgumentException("Attachment missing or not owned by submitter");
        }
        // skill_file has no lifecycle-state column. Existing ownership + downloadable bytes define availability.
        MessageDigest digest = sha256();
        long size = 0;
        try (InputStream input = files.download(reference.fileId(), owner).getInputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                size += count;
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Attachment is unavailable", e);
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        String version = file.getUpdatedAt() != null ? file.getUpdatedAt().toString()
                : file.getCreatedAt() != null ? file.getCreatedAt().toString() : hash;
        return new SkillReviewSnapshot.Attachment(file.getId(), reference.referenceType(), file.getFilename(),
                file.getFileType(), file.getDescription(), size, version, hash);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record BuiltSnapshot(SkillReviewSnapshot snapshot, String canonicalJson, String snapshotHash) {
        public boolean directApply() { return "PERSONAL".equals(snapshot.configuration().visibility()); }
    }
}
