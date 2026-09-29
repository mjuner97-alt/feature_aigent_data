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
package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.skillManager.entity.NotificationConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 通用通知配置表(notification_config)MyBatis Mapper。
 */
@Mapper
public interface NotificationConfigMapper {

    /** 按 (target_type, target_id) 查询配置记录;不存在返回 null。 */
    NotificationConfig selectByTarget(@Param("targetType") String targetType,
                                      @Param("targetId") Long targetId);

    /** 插入配置记录,(target_type, target_id) 唯一索引兜底幂等;回填自增主键。 */
    void insertConfig(NotificationConfig config);
}
