-- 为指定的 3 名人员补充 2026 年 7-10 月个人 Skill 模拟数据。
-- 目标库：openGauss（skill_manage 所在库）。
--
-- 规则：
--   1. 每人每月 4 条，共 48 条；
--   2. Skill 内容取自其他人员已有的、未删除且已启用的 Skill；
--   3. 新数据状态统一为 INACTIVE，可见性统一为 PERSONAL；
--   4. 不写 developer_pl_person_info_t，不模拟人员台账；
--   5. 以 owner_user_id + name 判断，脚本可重复执行而不重复插入。

BEGIN;

WITH target_person (person_no, owner_user_id) AS (
    VALUES
        (1, '555183084'),
        (2, '555079102'),
        (3, '555033645')
),
target_month (month_no, skill_month, month_start) AS (
    VALUES
        (1, '202607', TIMESTAMP '2026-07-01 09:00:00'),
        (2, '202608', TIMESTAMP '2026-08-01 09:00:00'),
        (3, '202609', TIMESTAMP '2026-09-01 09:00:00'),
        (4, '202610', TIMESTAMP '2026-10-01 09:00:00')
),
target_slot (slot_no) AS (
    VALUES (1), (2), (3), (4)
),
target_data AS (
    SELECT
        ((p.person_no - 1) * 16 + (m.month_no - 1) * 4 + x.slot_no) AS seq_no,
        p.owner_user_id,
        m.skill_month,
        x.slot_no,
        m.month_start
            + ((x.slot_no * 5 + p.person_no) * INTERVAL '1 day')
            + (x.slot_no * INTERVAL '1 hour') AS created_at
    FROM target_person p
    CROSS JOIN target_month m
    CROSS JOIN target_slot x
),
source_skill AS (
    SELECT
        s.id,
        s.name,
        s.description,
        s.content,
        s.category,
        s.tags,
        ROW_NUMBER() OVER (ORDER BY s.created_at, s.id) AS rn
    FROM skill_manage s
    WHERE s.deleted_at IS NULL
      AND s.status = 'ACTIVE'
      AND COALESCE(TRIM(s.content), '') <> ''
      AND s.owner_user_id NOT IN ('555183084', '555079102', '555033645')
),
source_count AS (
    SELECT COUNT(*) AS total FROM source_skill
),
rows_to_insert AS (
    SELECT
        t.owner_user_id,
        t.skill_month,
        t.slot_no,
        t.created_at,
        s.description,
        s.content,
        s.category,
        s.tags,
        -- 认证号和月份放在名称前部，避免源名称较长时截掉唯一标识。
        LEFT(
            '模拟个人Skill-' || t.owner_user_id || '-' || t.skill_month || '-' || t.slot_no || '-' || s.name,
            128
        ) AS skill_name
    FROM target_data t
    CROSS JOIN source_count c
    JOIN source_skill s
      ON s.rn = MOD(t.seq_no - 1, NULLIF(c.total, 0)) + 1
    WHERE c.total > 0
)
INSERT INTO skill_manage (
    name,
    description,
    content,
    category,
    tags,
    owner_user_id,
    status,
    like_count,
    retrieval_name,
    created_at,
    updated_at,
    deleted_at,
    visibility
)
SELECT
    r.skill_name,
    r.description,
    r.content,
    r.category,
    r.tags,
    r.owner_user_id,
    'INACTIVE',
    0,
    NULL,
    r.created_at,
    r.created_at,
    NULL,
    'PERSONAL'
FROM rows_to_insert r
WHERE NOT EXISTS (
    SELECT 1
    FROM skill_manage old
    WHERE old.owner_user_id = r.owner_user_id
      AND old.name = r.skill_name
);

-- 将 BIGSERIAL 序列同步到当前最大主键，避免后续自动生成的 id 与已有记录冲突。
SELECT SETVAL(
    PG_GET_SERIAL_SEQUENCE('skill_manage', 'id'),
    COALESCE((SELECT MAX(id) FROM skill_manage), 1),
    EXISTS (SELECT 1 FROM skill_manage)
);

COMMIT;

-- 执行后校验：预期每人 16 条、合计 48 条，且全部为 INACTIVE + PERSONAL。
SELECT
    owner_user_id AS "统一认证号",
    COUNT(*) AS "Skill数量",
    COUNT(DISTINCT TO_CHAR(created_at, 'YYYY-MM')) AS "覆盖月份数",
    MIN(created_at) AS "最早创建时间",
    MAX(created_at) AS "最晚创建时间",
    MIN(status) AS "状态",
    MIN(visibility) AS "可见性"
FROM skill_manage
WHERE owner_user_id IN ('555183084', '555079102', '555033645')
  AND name LIKE '模拟个人Skill-%'
  AND created_at >= TIMESTAMP '2026-07-01 00:00:00'
  AND created_at <  TIMESTAMP '2026-11-01 00:00:00'
GROUP BY owner_user_id
ORDER BY owner_user_id;
