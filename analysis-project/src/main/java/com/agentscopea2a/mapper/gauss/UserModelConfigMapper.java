/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.mapper.gauss;

import com.agentscopea2a.entity.UserModelConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 用户模型配置 Mapper (GaussDB) */
@Mapper
public interface UserModelConfigMapper {

    UserModelConfig selectByUserId(String userId);

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