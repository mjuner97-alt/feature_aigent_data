package com.agentscopea2a.v2.skillManager.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 业务库团队配置 Mapper，维护长任务报告的默认通知人员。 */
public interface TeamConfigMapper {
    /** 按组织和关键词分页查询已保存配置。 */
    List<Map<String, Object>> list(@Param("keyword") String keyword, @Param("department") String department, @Param("team") String team, @Param("limit") int limit, @Param("offset") long offset);
    /** 统计与列表使用相同筛选条件的配置条数。 */
    int count(@Param("keyword") String keyword, @Param("department") String department, @Param("team") String team);
    /** 查询已保存配置对应的部门和统计组。 */
    List<Map<String, Object>> options();
    /** 按主键查询一条配置。 */
    Map<String, Object> get(long id);
    /** 检查同部门、同统计组下是否已配置该认证号。 */
    int countExisting(@Param("department") String department, @Param("team") String team, @Param("userId") String userId);
    /** 新增配置，并将生成的主键写回 row 的 id。 */
    int insert(Map<String, Object> row);
    /** 按主键更新配置。 */
    int update(Map<String, Object> row);
    /** 仅更新通知类型和更新时间。 */
    int updateRecipientType(@Param("id") long id, @Param("type") String type);
    /** 按主键删除配置，返回受影响行数。 */
    int delete(long id);
}
