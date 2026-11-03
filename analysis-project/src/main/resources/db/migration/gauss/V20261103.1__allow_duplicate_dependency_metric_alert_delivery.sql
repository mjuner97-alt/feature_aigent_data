-- 同一天允许重复发送预警，因此移除 alert_date 唯一约束。
ALTER TABLE dependency_metric_alert_delivery
    DROP CONSTRAINT IF EXISTS uk_dependency_metric_alert_delivery_date;
