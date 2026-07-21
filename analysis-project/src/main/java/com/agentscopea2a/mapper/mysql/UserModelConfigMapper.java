package com.agentscopea2a.mapper.mysql;

import com.agentscopea2a.entity.UserModelConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户模型配置 Mapper。
 *
 * <p>selectByUserId 未找到记录时返回 null。
 */
@Mapper
public interface UserModelConfigMapper {

    UserModelConfig selectByUserId(Long userId);

    /** 加载全部用户模型配置（用于过期定时检测）。 */
    List<UserModelConfig> selectAll();

    /**
     * 更新某用户「最近一次过期通知时间」（用于去重）。
     *
     * @param userId 用户 ID
     * @param time   通知时间
     * @return 受影响行数
     */
    int updateNotifiedAt(@Param("userId") Long userId, @Param("time") LocalDateTime time);
}
