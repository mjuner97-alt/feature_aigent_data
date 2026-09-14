package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.SkillReviewAudit;
import com.agentscopea2a.v2.skillManager.entity.SkillReviewDimension;
import com.agentscopea2a.v2.skillManager.entity.SkillReviewRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评审 Mapper - 不可变技能评审请求及受限审计数据的持久化边界。
 *
 * <p>请求/快照写入后不可修改;状态流转均需携带 expectedStatus,通过 CAS 方式更新。</p>
 */
@Mapper
public interface SkillReviewMapper {

    /** 新增一个评审请求。 */
    int insertRequest(SkillReviewRequest request);

    /** 按 ID 查询评审请求。 */
    SkillReviewRequest selectRequestById(@Param("id") Long id);

    SkillReviewRequest selectActiveRequestBySkillId(@Param("skillId") Long skillId);

    /** 查询某提交人当前待评审的请求。 */
    List<SkillReviewRequest> selectPendingRequestsBySubmitter(@Param("submitterUserId") String submitterUserId);

    /** 查询所有处于终审(待终审)阶段的请求。 */
    List<SkillReviewRequest> selectPendingFinalReviewRequests();

    /** 新增一个初审维度记录。 */
    int insertDimension(SkillReviewDimension dimension);

    /** 按请求 ID 查询该请求的全部初审维度。 */
    List<SkillReviewDimension> selectDimensionsByRequestId(@Param("requestId") Long requestId);

    /** 按发布目标查询其待评审的初审维度。 */
    List<SkillReviewDimension> selectPendingDimensionsByTarget(
            @Param("targetType") String targetType, @Param("targetId") String targetId);

    /** 追加一条审计记录(只增不改)。 */
    int insertAudit(SkillReviewAudit audit);

    /** 按请求 ID 查询其全部审计轨迹。 */
    List<SkillReviewAudit> selectAuditByRequestId(@Param("requestId") Long requestId);

    /** 仅从受限审计记录中还原真实初审操作人。 */
    List<String> selectActualDimensionReviewerUserIds(@Param("requestId") Long requestId);

    /** 终审:按期望状态 CAS 流转请求状态并记录终审意见/时间。 */
    int transitionRequestStatus(@Param("requestId") Long requestId,
                                @Param("expectedStatus") String expectedStatus,
                                @Param("status") String status,
                                @Param("finalComment") String finalComment,
                                @Param("finalReviewedAt") LocalDateTime finalReviewedAt);

    /** 初审:按期望状态 CAS 流转单个维度的状态并记录意见/时间。 */
    int transitionDimensionStatus(@Param("dimensionId") Long dimensionId,
                                  @Param("expectedStatus") String expectedStatus,
                                  @Param("status") String status,
                                  @Param("reviewComment") String reviewComment,
                                  @Param("reviewedAt") LocalDateTime reviewedAt);

    /** 请求被终审驳回/撤回时,按期望状态批量作废该请求下仍待评审的维度。 */
    int cancelPendingDimensions(@Param("requestId") Long requestId,
                                @Param("expectedStatus") String expectedStatus,
                                @Param("reviewedAt") LocalDateTime reviewedAt);

    int transitionRequestSimple(@Param("requestId") Long requestId,
                                @Param("expectedStatus") String expectedStatus,
                                @Param("status") String status);
}
