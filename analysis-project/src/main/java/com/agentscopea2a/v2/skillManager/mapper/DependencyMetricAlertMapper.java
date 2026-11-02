package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.DependencyMetricAlertDelivery;
import com.agentscopea2a.v2.skillManager.entity.SkillMetricReadiness;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.util.List;

/** 依赖指标预警投递记录的数据访问接口：负责查询指标就绪状态，并以受影响行数控制预警记录的抢占与状态更新。 */
@Mapper
public interface DependencyMetricAlertMapper {
    /** 查询指定日期各依赖指标的就绪状态，供预警服务判断是否需要发送通知。 */
    List<SkillMetricReadiness> selectReadiness(@Param("metricIds") List<Long> metricIds,
                                               @Param("dataDate") LocalDate dataDate);
    /** 抢占当天的预警投递记录；返回 1 表示当前调用成功取得发送权。 */
    int claim(@Param("alertDate") LocalDate alertDate, @Param("recipientSummary") String recipientSummary,
              @Param("content") String content);
    /** 更新预警投递结果，通常将状态置为已发送或失败并记录错误原因。 */
    int update(@Param("alertDate") LocalDate alertDate, @Param("status") String status,
               @Param("errorMessage") String errorMessage);
}
