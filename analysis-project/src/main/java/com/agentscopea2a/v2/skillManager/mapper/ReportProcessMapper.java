package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.ReportProcess;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 汇报流程定义的数据访问接口：封装流程分页查询、详情读取、创建更新以及软删除等数据库操作。 */
@Mapper
public interface ReportProcessMapper {
    /** 分页查询当前用户可见的汇报流程，offset/limit 用于数据库分页。 */
    List<com.agentscopea2a.v2.skillManager.dto.ReportProcessSummaryDto> selectPage(@Param("userId") String userId, @Param("scope") String scope, @Param("keyword") String keyword, @Param("createdBy") String createdBy, @Param("offset") long offset, @Param("limit") int limit);
    /** 统计与分页筛选条件一致的流程数量。 */
    long countPage(@Param("userId") String userId, @Param("scope") String scope, @Param("keyword") String keyword, @Param("createdBy") String createdBy);
    /** 按业务编码读取流程，供外部引用和唯一性校验使用。 */
    ReportProcess selectByCode(@Param("code") String code);
    /** 查询用户可浏览的全部流程；mineOnly=true 时仅返回创建人自己的流程。 */
    List<ReportProcess> selectAllVisible(@Param("userId") String userId, @Param("mineOnly") boolean mineOnly);
    /** 查询浏览场景下的全部流程。 */
    List<ReportProcess> selectAllForBrowse();
    /** 按主键读取流程详情。 */
    ReportProcess selectById(@Param("id") Long id);
    /** 按名称精确查询，用于创建或修改时的重名校验。 */
    ReportProcess selectByName(@Param("name") String name);
    /** 新增流程定义并回填数据库生成的主键。 */
    void insert(ReportProcess flow);
    /** 更新流程定义内容。 */
    void update(ReportProcess flow);
    /** 仅切换流程启用状态。 */
    void updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    /** 软删除流程，保留历史数据供审计和执行记录关联。 */
    void softDelete(@Param("id") Long id);
}

