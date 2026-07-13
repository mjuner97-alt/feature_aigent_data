package com.agentscopea2a.mapper.db1;

import com.agentscopea2a.entity.UserModelConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 根据用户 ID 查询其模型配置。
 * 如果未找到对应记录，返回 null。
 */
@Mapper
public interface UserModelConfigMapper {

    UserModelConfig selectByUserId(Long userId);
}
