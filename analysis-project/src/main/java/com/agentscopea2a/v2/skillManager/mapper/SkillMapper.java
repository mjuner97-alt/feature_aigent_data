/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.dto.SkillFileReferenceItem;
import com.agentscopea2a.v2.skillManager.dto.SkillListQuery;
import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.entity.SkillApproval;
import com.agentscopea2a.v2.skillManager.entity.SkillDraft;
import com.agentscopea2a.v2.skillManager.entity.SkillFile;
import com.agentscopea2a.v2.skillManager.entity.SkillFileReference;
import com.agentscopea2a.v2.skillManager.entity.SkillLike;
import com.agentscopea2a.v2.skillManager.entity.SkillOperationHistory;
import com.agentscopea2a.v2.skillManager.entity.SkillPublish;
import com.agentscopea2a.v2.skillManager.entity.SkillReference;
import com.agentscopea2a.v2.skillManager.entity.SkillUserDisable;
import com.agentscopea2a.v2.skillManager.entity.SkillVersionHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/**
 * Skill 合并 Mapper - 维护 skill_manage / skill_like / skill_reference /
 * skill_publish / skill_approval / skill_draft / skill_version_history /
 * skill_operation_history / skill_user_disable 等表的 CRUD 与查询。
 * 包路径受 {@code MySQLConfig.@MapperScan(basePackages = "com.agentscopea2a.mapper.mysql")} 约束。
 */
@Mapper
public interface SkillMapper {

    // ==================== skill_manage ====================

    int insertSkill(Skill skill);

    Skill selectById(@Param("id") Long id);

    int updateSkill(Skill skill);

    int softDelete(@Param("id") Long id);

    boolean existsByName(@Param("name") String name);

    Long selectLikeCount(@Param("id") Long id);

    int incrementLikeCount(@Param("id") Long id);

    int decrementLikeCount(@Param("id") Long id);

    List<Skill> selectByIds(@Param("ids") List<Long> ids);

    /** 按 retrieval_name 查 skill_manage.content(检索 Hook 读 body 用);DELETED 的不返回。 */
    String selectContentByRetrievalName(@Param("retrievalName") String retrievalName);

    /** 按 retrieval_name + owner_user_id 查询单条 ACTIVE skill。 */
    Skill selectByRetrievalNameAndOwner(
            @Param("retrievalName") String retrievalName,
            @Param("ownerUserId") String ownerUserId);

    /** 查询某用户自己创建或引用的 skill 的 retrieval_name(过滤 NULL)。 */
    List<String> selectActiveRetrievalNamesByUser(@Param("userId") String userId);

    /** 查询某用户自己创建或引用的全部 ACTIVE skill(完整行)。 */
    List<Skill> selectActiveByUser(@Param("userId") String userId);

    /** 按 retrieval_name + owner_user_id 软删除。 */
    int softDeleteByRetrievalNameAndOwner(
            @Param("retrievalName") String retrievalName,
            @Param("ownerUserId") String ownerUserId);

    /** 按 retrieval_name + owner_user_id 更新(name/description/content/category/tags/updated_at)。 */
    int updateByRetrievalNameAndOwner(Skill skill);

    /** 按 retrieval_name + owner_user_id 判断 ACTIVE skill 是否存在。 */
    boolean existsByRetrievalNameAndOwner(
            @Param("retrievalName") String retrievalName,
            @Param("ownerUserId") String ownerUserId);

    /** 列表查询:按 view/sort/category/tag/keyword 过滤 + 分页。 */
    List<Skill> selectList(SkillListQuery q);

    /** 查询全部 ACTIVE Skill 的去重 tag 列表。 */
    List<String> selectAllTags();

    // ==================== skill_like ====================

    int insertSkillLike(SkillLike like);

    SkillLike selectLikeByUserSkill(@Param("userId") String userId, @Param("skillId") Long skillId);

    int deleteLikeByUserSkill(@Param("userId") String userId, @Param("skillId") Long skillId);

    /** 当前用户在给定 skillId 集合中已点赞的 skillId(列表行 liked 标记批量计算)。 */
    Set<Long> selectLikedSkillIds(@Param("userId") String userId, @Param("ids") List<Long> ids);

    // ==================== skill_reference ====================

    int insertSkillReference(SkillReference ref);

    int deleteReferenceByCreatorTarget(@Param("creator") String creator, @Param("skillId") Long skillId);

    boolean existsReferenceByCreatorTarget(@Param("creator") String creator, @Param("skillId") Long skillId);

    /** 当前用户引用过的 skillId 列表(我使用的)。 */
    List<Long> selectReferencedSkillIdsByCreator(@Param("creator") String creator);

    /** 当前用户在给定集合中已引用的 skillId(列表行 used 标记批量计算)。 */
    Set<Long> selectUsedSkillIds(@Param("creator") String creator, @Param("ids") List<Long> ids);

    /** 引用某 Skill 的用户列表(被引用人数展示)。 */
    List<String> selectReferencersBySkillId(@Param("skillId") Long skillId);

    // ==================== skill_publish ====================

    int insertSkillPublish(SkillPublish publish);

    SkillPublish selectPublishById(@Param("id") Long id);

    int updatePublishStatus(@Param("id") Long id, @Param("status") String status,
                            @Param("approver") String approver, @Param("comment") String comment);

    List<SkillPublish> selectPublishBySkillId(@Param("skillId") Long skillId);

    boolean hasApprovedBySkillId(@Param("skillId") Long skillId);

    /** 判断指定 Skill 是否存在 PENDING 状态的发布记录(审批中不可编辑/删除)。 */
    boolean hasPendingBySkillId(@Param("skillId") Long skillId);

    List<SkillPublish> selectApprovedBySkillId(@Param("skillId") Long skillId);

    List<SkillPublish> selectPendingPublishByApprover(@Param("approverUserId") String approverUserId);

    /** 查询指定审批人处理过的发布记录(APPROVED/REJECTED,按 approver 字段匹配)。 */
    List<SkillPublish> selectHistoryByApprover(@Param("approverUserId") String approverUserId);

    List<SkillPublish> selectApprovedBySkillIds(@Param("skillIds") List<Long> skillIds);

    // ==================== skill_approval ====================

    int insertSkillApproval(SkillApproval approval);

    List<SkillApproval> selectApprovalByPublishId(@Param("publishId") Long publishId);

    List<SkillApproval> selectApprovalByDraftId(@Param("draftId") Long draftId);

    // ==================== skill_draft ====================

    int insertSkillDraft(SkillDraft draft);

    SkillDraft selectDraftById(@Param("id") Long id);

    SkillDraft selectPendingDraftBySkillId(@Param("skillId") Long skillId);

    int updateDraftStatus(@Param("id") Long id, @Param("status") String status,
                          @Param("approver") String approver, @Param("comment") String comment);

    int updateDraftContent(SkillDraft draft);

    List<SkillDraft> selectPendingDraftByApprover(@Param("approverUserId") String approverUserId);

    // ==================== skill_version_history ====================

    int insertSkillVersionHistory(SkillVersionHistory history);

    List<SkillVersionHistory> selectVersionBySkillId(@Param("skillId") Long skillId);

    Integer selectMaxVersion(@Param("skillId") Long skillId);

    // ==================== skill_operation_history ====================

    int insertSkillOperationHistory(SkillOperationHistory history);

    List<SkillOperationHistory> selectOperationBySkillId(@Param("skillId") Long skillId);

    List<SkillOperationHistory> selectByOperator(@Param("operator") String operator);

    // ==================== skill_user_disable ====================

    int insertSkillUserDisable(SkillUserDisable disable);

    int deleteDisableByUserSkill(@Param("userId") String userId, @Param("skillId") Long skillId);

    boolean existsDisableByUserSkill(@Param("userId") String userId, @Param("skillId") Long skillId);

    Set<Long> selectDisabledSkillIds(@Param("userId") String userId, @Param("skillIds") List<Long> skillIds);

    // ==================== skill_file ====================

    int insertSkillFile(SkillFile skillFile);

    SkillFile selectFileById(@Param("id") Long id);

    SkillFile selectFileByUserIdAndFilename(@Param("userId") String userId, @Param("filename") String filename);

    List<SkillFile> selectFilesByUserId(@Param("userId") String userId, @Param("fileType") String fileType);

    int updateSkillFile(SkillFile skillFile);

    int deleteSkillFile(@Param("id") Long id);

    // ==================== skill_file_reference ====================

    int insertSkillFileReference(SkillFileReference ref);

    boolean existsSkillFileReference(@Param("skillId") Long skillId, @Param("fileId") Long fileId);

    int deleteSkillFileReference(@Param("skillId") Long skillId, @Param("fileId") Long fileId);

    int deleteFileReferencesByFileId(@Param("fileId") Long fileId);

    List<SkillFileReferenceItem> selectSkillFileReferences(@Param("skillId") Long skillId);
}
