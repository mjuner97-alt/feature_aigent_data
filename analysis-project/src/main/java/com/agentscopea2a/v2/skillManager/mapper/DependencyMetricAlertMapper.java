package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.DependencyMetricAlertDelivery;
import com.agentscopea2a.v2.skillManager.entity.SkillMetricReadiness;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.util.List;

/** 依赖指标预警投递记录的数据访问接口：负责查询指标就绪状态，为每次通知创建独立投递记录并更新其状态。 */
@Mapper
public interface DependencyMetricAlertMapper {
    /** 查询指定日期各依赖指标的就绪状态，供预警服务判断是否需要发送通知。 */
    List<SkillMetricReadiness> selectReadiness(@Param("metricIds") List<Long> metricIds,
                                               @Param("dataDate") LocalDate dataDate);
    /** 为本次通知插入独立记录，并回填生成的主键。 */
    int claim(DependencyMetricAlertDelivery delivery);
    /** 按投递记录主键更新本次通知结果。 */
    int update(@Param("id") Long id, @Param("status") String status,
               @Param("errorMessage") String errorMessage);
}
