package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/**
 * 技能评审快照 - 提交评审时冻结的完整配置(不可变),后续初评/终评阶段直接读取保存的 JSON,
 * 而不是重新构建,保证各阶段看到的配置完全一致。
 *
 * @param skillId        被评审的技能 ID
 * @param ownerUserId    技能归属人(提交人)
 * @param retrievalName  技能检索名(page_<id>)
 * @param configuration  完整的候选配置
 * @param attachments    技能绑定附件的元信息(含内容 SHA-256,用于校验附件未被篡改)
 */
public record SkillReviewSnapshot(Long skillId, String ownerUserId, String retrievalName,
                                  SkillReviewSubmission configuration,
                                  List<Attachment> attachments) {
    /** 构造时对附件列表做不可变拷贝,保证快照内容不可变。 */
    public SkillReviewSnapshot {
        attachments = List.copyOf(attachments);
    }

    /**
     * 附件快照信息 - 记录附件的元数据与内容指纹。
     *
     * @param fileId         附件 ID
     * @param referenceType  引用类型
     * @param filename       文件名
     * @param fileType       文件类型
     * @param description    附件描述
     * @param fileSize       文件字节数
     * @param contentVersion 附件内容版本(取文件的 updated_at/created_at)
     * @param sha256         附件内容的 SHA-256 摘要
     */
    public record Attachment(Long fileId, String referenceType, String filename, String fileType,
                             String description, long fileSize, String contentVersion, String sha256) { }
}
