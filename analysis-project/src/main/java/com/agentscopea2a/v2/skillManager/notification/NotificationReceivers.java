package com.agentscopea2a.v2.skillManager.notification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 通知收件人名单的存储格式与解析工具。
 *
 * <p>存储格式:逗号分隔的 userId(统一认证号),值域与 {@code skill_job.created_by} /
 * {@code skill_flow_execution.trigger_user_id} 一致。发送时整个名单一次性批量透传邮件
 * {@code toUserList}(一份报告同时发给多人,不循环单发)。
 */
public final class NotificationReceivers {

    private NotificationReceivers() {
    }

    /** 解析逗号分隔名单:去空白、去重(保序);null/空串返回空列表。 */
    public static List<String> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                result.add(id);
            }
        }
        return new ArrayList<>(result);
    }

    /** 序列化为逗号分隔存储串;空名单返回 null(入库即"未配置")。 */
    public static String toCsv(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String id : userIds) {
            if (id != null && !id.isBlank()) {
                distinct.add(id.trim());
            }
        }
        return distinct.isEmpty() ? null : String.join(",", distinct);
    }
}
