package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.LongTaskTag;
import com.agentscopea2a.v2.skillManager.entity.LongTaskType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 流程类型和流程标签的数据访问接口，具体 SQL 在同名 XML 中。 */
@Mapper
public interface LongTaskConfigMapper {
    /** 查询类型列表，可选按启用状态过滤。 */
    List<LongTaskType> selectTypes(@Param("enabled") Boolean enabled);
    /** 分页查询类型列表。 */
    List<LongTaskType> selectTypesPage(@Param("enabled") Boolean enabled, @Param("offset") int offset, @Param("limit") int limit);
    /** 统计筛选条件下的类型总数。 */
    long countTypes(@Param("enabled") Boolean enabled);
    /** 按主键查询类型。 */
    LongTaskType selectTypeById(@Param("id") Long id);
    /** 按名称查询类型，用于重复校验。 */
    LongTaskType selectTypeByName(@Param("name") String name);
    /** 新增类型，主键由数据库生成。 */
    void insertType(LongTaskType type);
    /** 更新类型基本信息。 */
    void updateType(LongTaskType type);
    /** 单独更新启用状态。 */
    int updateTypeEnabled(@Param("id") Long id, @Param("enabled") Boolean enabled);
    /** 删除类型并返回实际删除行数。 */
    int deleteType(@Param("id") Long id);
    /** 统计仍引用该类型的有效流程数量。 */
    long countFlowsByTypeId(@Param("typeId") Long typeId);
    /** 查询全部标签。 */
    List<LongTaskTag> selectTags();
    /** 按名称查询标签。 */
    LongTaskTag selectTagByName(@Param("name") String name);
    /** 新增标签。 */
    void insertTag(LongTaskTag tag);
    /** 查询某个流程关联的标签。 */
    List<LongTaskTag> selectTagsByFlowId(@Param("flowId") Long flowId);
    /** 删除流程的全部标签关联。 */
    void deleteFlowTags(@Param("flowId") Long flowId);
    /** 新增流程与标签的关联。 */
    void insertFlowTag(@Param("flowId") Long flowId, @Param("tagId") Long tagId);
}



