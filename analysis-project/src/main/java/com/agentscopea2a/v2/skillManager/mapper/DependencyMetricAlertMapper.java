package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.DependencyMetricAlertDelivery;
import com.agentscopea2a.v2.skillManager.entity.SkillMetricReadiness;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DependencyMetricAlertMapper {
    List<SkillMetricReadiness> selectReadiness(@Param("metricIds") List<Long> metricIds,
                                               @Param("dataDate") LocalDate dataDate);
    int claim(@Param("alertDate") LocalDate alertDate, @Param("recipientSummary") String recipientSummary,
              @Param("content") String content);
    int update(@Param("alertDate") LocalDate alertDate, @Param("status") String status,
               @Param("errorMessage") String errorMessage);
}
