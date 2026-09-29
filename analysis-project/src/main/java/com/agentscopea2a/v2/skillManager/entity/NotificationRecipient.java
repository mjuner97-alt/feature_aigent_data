package com.agentscopea2a.v2.skillManager.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知配置收件人关系表(notification_recipient)实体。
 * 保存业务通知与用户之间的多对多关系;第一期固定 TO + EMAIL。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRecipient {

    /** 收件人类型,第一期固定为 TO,预留 CC/BCC。 */
    public static final String RECIPIENT_TYPE_TO = "TO";

    /** 通知渠道,第一期固定为 EMAIL。 */
    public static final String CHANNEL_EMAIL = "EMAIL";

    /** 收件人关系主键。 */
    private Long id;

    /** 关联 notification_config.id。 */
    private Long configId;

    /** 收件人用户 ID,需通过人员服务校验。 */
    private String userId;

    /** 触发来源范围: DEFAULT、AUTO_METRIC、MANUAL、CHAT。 */
    private String triggerType;

    /** 收件人类型,第一期固定为 TO,预留 CC/BCC。 */
    private String recipientType;

    /** 通知渠道,第一期固定为 EMAIL。 */
    private String channel;

    /** 该收件人关系是否启用。 */
    private Boolean enabled;

    /** 关系创建时间。 */
    private LocalDateTime createdAt;

    /** 关系最后更新时间。 */
    private LocalDateTime updatedAt;
}
