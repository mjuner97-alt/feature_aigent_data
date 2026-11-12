package com.agentscopea2a.mapper.gaussCommon;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 公共库人员台账 Mapper，为团队配置提供只读人员来源。 */
public interface TeamLedgerMapper {
    /** 分页查询最新统计月份内去重后的人员。 */
    List<Map<String, Object>> list(@Param("keyword") String keyword, @Param("department") String department, @Param("team") String team, @Param("limit") int limit, @Param("offset") long offset);
    /** 统计同一筛选范围内去重后的人员总数。 */
    int count(@Param("keyword") String keyword, @Param("department") String department, @Param("team") String team);
    /** 查询去重后人员所在的部门和统计组。 */
    List<Map<String, Object>> options();
    /** 按认证号批量取回选中人员的台账记录，用于导入前校验。 */
    List<Map<String, Object>> findByUserIds(@Param("ids") List<String> ids);
}
