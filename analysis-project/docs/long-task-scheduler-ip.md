# 长任务调度 IP 配置

部署前执行迁移 `V20261026.1__long_task_scheduler_ip.sql`，再将配置表中的 IP 填为生产执行服务器**本机网卡地址**：

```sql
UPDATE ai_chat_runtime_config
SET config_value = '10.20.30.40', updated_at = CURRENT_TIMESTAMP
WHERE config_key = 'long_task_scheduler_ip';
```

示例 IP 必须替换为实际生产地址；如果经过 NAT，应填写服务器网卡地址，而非外部映射地址。容器部署应填写应用进程可见的网卡 IP。只支持单个 IPv4/IPv6 地址，不支持域名、多个 IP、通配符和回环地址。

迁移默认值为空，**未配置时暂停自动触发的长任务（AUTO_METRIC），手动（MANUAL）和聊天（CHAT）执行不受影响**。数据库读取失败、网卡读取失败、IP 不匹配也会阻止自动任务，不会降级为允许。日志会输出 `Long-task scheduler blocked` 及原因，状态发生变化时再次记录。

配置在下一次入口校验时生效，无须重启；定时扫描间隔约 30 秒。已开始的节点不会被强行中断。读取配置不使用聊天会话缓存。

限制按执行记录的 trigger_type 判断，与流程定义上的 auto_trigger_enabled 开关分开：同一个开启自动触发的流程，手动或聊天执行仍不受 IP 限制。自动任务的创建、启动恢复、指标就绪处理、超时清理和节点认领仅由授权机器处理；手动/聊天任务在任意机器均可正常调度。查询在 LIMIT 之前排除无权限的自动记录，避免自动任务积压阻塞手动任务。本功能不限制其他模块的定时任务。

自动触发开关另已补齐 MyBatis 查询列及结果映射；此前保存写入成功，但详情和调度读取不到开关，导致看起来未保存且不会自动触发。

## 为已配置定时规则的历史长任务开启自动触发

以下为手工执行的 Gauss SQL，仅更新未删除且配置包含星期规则的长任务；不改变流程自身的 enabled 状态：

```sql
UPDATE skill_flow
SET auto_trigger_enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE deleted_at IS NULL
  AND schedule_rules ~ '"(MON|TUE|WED|THU|FRI|SAT|SUN)"'
  AND (auto_trigger_enabled IS NULL OR auto_trigger_enabled = FALSE);
```

未配置星期规则、空数组和空对象均不会匹配。SQL 未在生产库执行。
