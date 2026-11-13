-- 为指定的 3 名人员补充 2026 年 7-10 月个人 Skill 模拟数据。
-- 目标库：openGauss（skill_manage 所在库）。
--
-- 规则：
--   1. 每人每月 4 条，共 48 条；
--   2. Skill 内容取自其他人员已有的、未删除且已启用的 Skill；
--   3. 新数据状态统一为 INACTIVE，可见性统一为 PERSONAL；
--   4. 不写 developer_pl_person_info_t，不模拟人员台账；
--   5. 以认证号和模拟时间识别已生成的记录。

BEGIN;

-- 先生成本次造数的目标时间；临时表可同时供新增和存量模拟数据更新时间使用。
CREATE TEMP TABLE mock_skill_target_data AS
WITH target_person (person_no, owner_user_id) AS (
    VALUES
        (1, '555183084'),
        (2, '555079102'),
        (3, '555033645')
),
target_month (month_no, skill_month) AS (
    VALUES
        (1, '202607'),
        (2, '202608'),
        (3, '202609'),
        (4, '202610')
),
target_slot (slot_no) AS (
    VALUES (1), (2), (3), (4)
)
SELECT
    ((p.person_no - 1) * 16 + (m.month_no - 1) * 4 + x.slot_no) AS seq_no,
    p.owner_user_id,
    m.skill_month,
    x.slot_no,
    -- 直接按 YYYYMMDDHH24MISS 生成完整时间，确保时、分、秒均有值。
    TO_TIMESTAMP(
        m.skill_month
        || LPAD((x.slot_no * 5 + p.person_no)::VARCHAR, 2, '0')
        || LPAD((8 + x.slot_no + p.person_no)::VARCHAR, 2, '0')
        || LPAD((1 + MOD(x.slot_no * 13 + p.person_no * 7, 59))::VARCHAR, 2, '0')
        || LPAD((1 + MOD(x.slot_no * 17 + p.person_no * 11, 59))::VARCHAR, 2, '0'),
        'YYYYMMDDHH24MISS'
    )::TIMESTAMP AS created_at
FROM target_person p
CROSS JOIN target_month m
CROSS JOIN target_slot x;

-- 固化源 Skill 与目标人员、月份的对应关系，供改名、新增和更新时间共同使用。
CREATE TEMP TABLE mock_skill_rows_to_insert AS
WITH
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
--       AND COALESCE(TRIM(s.content), '') <> ''
      AND s.owner_user_id NOT IN ('555183084', '555079102', '555033645')
),
source_count AS (
    SELECT COUNT(*) AS total FROM source_skill
)
SELECT
    t.owner_user_id,
    t.skill_month,
    t.slot_no,
    t.created_at,
    s.description,
    s.content,
    s.category,
    s.tags,
    -- 名称列最多 128 字符，预留 7 个字符给连字符和月份。
    LEFT(s.name, 121) || '-' || t.skill_month AS skill_name
FROM mock_skill_target_data t
CROSS JOIN source_count c
CROSS JOIN source_skill s
WHERE s.rn = MOD(CAST(t.seq_no - 1 AS BIGINT), NULLIF(c.total, 0)) + 1;

-- 脚本旧版已执行过时，将旧的长名称同步改成“源 Skill 名称-月份”。
UPDATE skill_manage old
SET name = r.skill_name,
    created_at = r.created_at,
    updated_at = r.created_at
FROM mock_skill_rows_to_insert r
WHERE old.owner_user_id = r.owner_user_id
  AND old.status = 'INACTIVE'
  AND old.visibility = 'PERSONAL'
  AND old.deleted_at IS NULL
  AND old.name LIKE (
      '模拟个人Skill-' || r.owner_user_id || '-' || r.skill_month || '-' || r.slot_no || '-%'
  );

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
FROM mock_skill_rows_to_insert r
WHERE NOT EXISTS (
    SELECT 1
    FROM skill_manage old
    WHERE old.owner_user_id = r.owner_user_id
      AND old.created_at = r.created_at
      AND RIGHT(old.name, 7) = '-' || r.skill_month
      AND old.status = 'INACTIVE'
      AND old.visibility = 'PERSONAL'
      AND old.deleted_at IS NULL
);

-- 将 BIGSERIAL 序列同步到当前最大主键，避免后续自动生成的 id 与已有记录冲突。
SELECT SETVAL(
    PG_GET_SERIAL_SEQUENCE('skill_manage', 'id'),
    COALESCE((SELECT MAX(id) FROM skill_manage), 1),
    EXISTS (SELECT 1 FROM skill_manage)
);

-- 当前 openGauss 环境不支持提交时自动删除临时表，提交前显式清理。
DROP TABLE mock_skill_rows_to_insert;
DROP TABLE mock_skill_target_data;

COMMIT;

-- 执行后校验：预期每人 16 条、合计 48 条，且全部为 INACTIVE + PERSONAL。
SELECT
    owner_user_id AS "统一认证号",
    COUNT(*) AS "Skill数量",
    COUNT(DISTINCT TO_CHAR(created_at, 'YYYY-MM')) AS "覆盖月份数",
    TO_CHAR(MIN(created_at), 'YYYY-MM-DD HH24:MI:SS') AS "最早创建时间",
    TO_CHAR(MAX(created_at), 'YYYY-MM-DD HH24:MI:SS') AS "最晚创建时间",
    MIN(status) AS "状态",
    MIN(visibility) AS "可见性"
FROM skill_manage
WHERE owner_user_id IN ('555183084', '555079102', '555033645')
--   AND name LIKE '模拟个人Skill-%'
  AND created_at >= TIMESTAMP '2026-07-01 00:00:00'
  AND created_at <  TIMESTAMP '2026-11-01 00:00:00'
GROUP BY owner_user_id
ORDER BY owner_user_id;
