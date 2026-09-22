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

import com.agentscopea2a.v2.skillManager.entity.NotificationRecipient;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 通知配置收件人关系表(notification_recipient)MyBatis Mapper。
 */
@Mapper
public interface NotificationRecipientMapper {

    /** 查询某配置下启用的收件人关系,按 id 升序。 */
    List<NotificationRecipient> selectEnabledByConfigId(@Param("configId") Long configId);

    /** 按 (target_type, target_id) 联查启用收件人关系(发送侧主读取来源,省一次配置查询)。 */
    List<NotificationRecipient> selectEnabledByTarget(@Param("targetType") String targetType,
                                                      @Param("targetId") Long targetId);

    List<NotificationRecipient> selectEnabledByTargetAndTrigger(@Param("targetType") String targetType,
                                                                @Param("targetId") Long targetId,
                                                                @Param("triggerType") String triggerType);

    /** 删除某配置下的全部收件人关系(全量替换的第一步,须在事务内使用)。 */
    void deleteByConfigId(@Param("configId") Long configId);

    void deleteByConfigIdAndTrigger(@Param("configId") Long configId,
                                    @Param("triggerType") String triggerType);

    /** 批量插入收件人关系(全量替换的第二步,须在事务内使用);调用方保证列表非空且无重复。 */
    void batchInsert(@Param("recipients") List<NotificationRecipient> recipients);
}
