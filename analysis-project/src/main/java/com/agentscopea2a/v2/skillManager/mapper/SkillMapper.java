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
import com.agentscopea2a.v2.skillManager.entity.SkillVisibleGrant;
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

    /** 按 retrieval_name 查询当前用户可访问的 skill(含显式引用 + 维度发布)。 */
    Skill selectByRetrievalNameAccessibleByUser(
            @Param("retrievalName") String retrievalName,
            @Param("userId") String userId);

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

    /**
     * 判断指定用户当前是否仍"可用"某个 Skill（available）。
     * 与 {@code SkillManageService.list()} 的 available 标记一致：
     * ACTIVE 且未软删 && (本人创建 || 已引用 || 所属维度已发布) && 未被该用户禁用。
     * 用于 SkillJob 执行前校验 createdBy 仍拥有该 Skill 权限。
     */
    boolean selectSkillAvailableForUser(
            @Param("skillId") Long skillId,
            @Param("userId") String userId);

    /** 列表查询:按 view/sort/category/tag/keyword 过滤 + 分页。 */
    List<Skill> selectList(SkillListQuery q);

    /** 查询全部 ACTIVE Skill 的去重 tag 列表(按用户可见范围过滤)。 */
    List<String> selectAllTags(@Param("userId") String userId);

    // ==================== skill_visible_grant(私有可见性授权) ====================

    /** 指定 Skill 的授权列表(供 owner 查看/管理授权)。 */
    List<SkillVisibleGrant> selectGrantsBySkill(@Param("skillId") Long skillId);

    /** 新增一条授权(幂等由唯一键保证)。 */
    int insertSkillVisibleGrant(SkillVisibleGrant grant);

    /** 删除一条授权。 */
    int deleteSkillVisibleGrant(@Param("skillId") Long skillId,
                                @Param("grantType") String grantType,
                                @Param("targetId") String targetId);

    /** 判断某条授权是否已存在。 */
    boolean existsSkillVisibleGrant(@Param("skillId") Long skillId,
                                    @Param("grantType") String grantType,
                                    @Param("targetId") String targetId);

    /** 指定 Skill 的授权条数(用于"加首个授权自动切 PRIVATE"判断)。 */
    long countGrantsBySkill(@Param("skillId") Long skillId);

    /**
     * 从给定 skillId 集合中,取当前用户(含其所属 统计组/部门)被授权命中的 skillId。
     * 供 {@code SkillManageService.list()} 计算 used 第四来源(授权即自动可用)。
     */
    Set<Long> selectGrantedSkillIds(@Param("userId") String userId, @Param("ids") List<Long> ids);

    /**
     * 判断当前用户对指定 Skill 是否命中任一授权(USER 点名 / 所属 GROUP / 所属 DEPARTMENT)。
     * 供 {@code SkillManageService.isVisible} 的单条可见性校验;PUBLIC 与 owner 由调用方先判。
     */
    boolean existsGrantForUser(@Param("skillId") Long skillId, @Param("userId") String userId);

    /**
     * 判断指定 Skill 是否已 APPROVED 发布到当前用户所属维度(legacy 兼容分支)。
     * 与 SkillMapper.xml 的 {@code visibleSkillIds} 里 UNION 的 {@code dimensionUsedSkillIds} 同口径,
     * 供 {@code SkillManageService.isVisible} 单条校验使用,保证 列表(SQL) 与 详情(Java) 判定一致。
     */
    boolean existsDimensionUsedForUser(@Param("skillId") Long skillId, @Param("userId") String userId);

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
