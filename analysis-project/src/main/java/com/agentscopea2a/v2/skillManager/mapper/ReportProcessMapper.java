package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.ReportProcess;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ReportProcessMapper {
    List<com.agentscopea2a.v2.skillManager.dto.ReportProcessSummaryDto> selectPage(@Param("userId") String userId, @Param("scope") String scope, @Param("keyword") String keyword, @Param("createdBy") String createdBy, @Param("offset") long offset, @Param("limit") int limit);
    long countPage(@Param("userId") String userId, @Param("scope") String scope, @Param("keyword") String keyword, @Param("createdBy") String createdBy);
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

