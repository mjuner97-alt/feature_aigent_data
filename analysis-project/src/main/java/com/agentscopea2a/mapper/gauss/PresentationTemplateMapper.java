package com.agentscopea2a.mapper.gauss;

import com.agentscopea2a.entity.PresentationTemplateEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** GaussDB mapper for presentation_template_registry. */
@Mapper
public interface PresentationTemplateMapper {
    PresentationTemplateEntry selectByTemplateId(@Param("templateId") String templateId);
    int countByTemplateId(@Param("templateId") String templateId);
    List<PresentationTemplateEntry> selectAll();
    PresentationTemplateEntry selectById(@Param("id") Long id);
    int insert(PresentationTemplateEntry entry);
    int update(PresentationTemplateEntry entry);
    int deleteById(@Param("id") Long id);
}
