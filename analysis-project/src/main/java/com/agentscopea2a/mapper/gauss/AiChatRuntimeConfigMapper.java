package com.agentscopea2a.mapper.gauss;

import com.agentscopea2a.entity.AiChatRuntimeConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiChatRuntimeConfigMapper {

    /**
     * 查询所有 AI 聊天运行时配置。
     *
     * @return 配置列表
     */
    List<AiChatRuntimeConfig> selectAll();

    /**
     * 根据配置键查询单条运行时配置。
     *
     * @param configKey 配置键
     * @return 对应的配置，查不到时返回 null
     */
    AiChatRuntimeConfig selectByConfigKey(@Param("configKey") String configKey);
}
