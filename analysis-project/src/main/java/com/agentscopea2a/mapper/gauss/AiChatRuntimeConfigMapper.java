package com.agentscopea2a.mapper.gauss;

import com.agentscopea2a.entity.AiChatRuntimeConfig;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AiChatRuntimeConfigMapper {
    List<AiChatRuntimeConfig> selectAll();
}
