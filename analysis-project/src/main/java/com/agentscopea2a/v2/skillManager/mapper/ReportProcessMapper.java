package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.ReportProcess;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ReportProcessMapper {
    ReportProcess selectByCode(@Param("code") String code);
    List<ReportProcess> selectAllVisible(@Param("userId") String userId, @Param("mineOnly") boolean mineOnly);
    List<ReportProcess> selectAllForBrowse();
    ReportProcess selectById(@Param("id") Long id);
    ReportProcess selectByName(@Param("name") String name);
    void insert(ReportProcess flow);
    void update(ReportProcess flow);
    void updateEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    void softDelete(@Param("id") Long id);
}

