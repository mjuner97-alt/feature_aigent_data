-- 验证脚本：与 VersionPlanKnowledgeHook.VERSION_PLAN_SQL 完全一致，期望返回
-- 2026年9月份版本 / 2026年10月份版本 / 2026年11月份版本（按投产日升序）
WITH RankedCalendar AS (
    SELECT
        火车开始时间,
        版本计划,
        CAST(常规投产日 AS DATE) AS 投产日_Date,
        DENSE_RANK() OVER (ORDER BY CAST(常规投产日 AS DATE)) AS date_rank,
        ABS(CAST(常规投产日 AS DATE) - CURRENT_DATE) AS diff_days,
        CAST(常规投产日 AS DATE) - CURRENT_DATE AS diff_sign
    FROM rdc_train_calendar
    WHERE
        火车类型 IN ('直接发布火车（四周）', '非直接发布火车（四周）（4+4）')
        AND CAST(常规投产日 AS DATE) BETWEEN CURRENT_DATE - INTERVAL '3' MONTH
                                          AND CURRENT_DATE + INTERVAL '3' MONTH
),
MinDiff AS (
    SELECT
        date_rank AS min_rank,
        diff_sign AS min_sign
    FROM RankedCalendar
    ORDER BY diff_days ASC
    LIMIT 1
)
SELECT
    rc.版本计划 AS 当前在建版本
FROM RankedCalendar rc
CROSS JOIN MinDiff md
WHERE
    CASE
        WHEN md.min_sign < 0 THEN
            rc.date_rank BETWEEN md.min_rank AND md.min_rank + 2
        WHEN md.min_sign > 0 THEN
            rc.date_rank BETWEEN md.min_rank - 1 AND md.min_rank + 1
        ELSE
            rc.date_rank BETWEEN md.min_rank AND md.min_rank + 2
    END
    AND CAST(rc.火车开始时间 AS DATE) <= CURRENT_DATE
ORDER BY rc.投产日_Date
