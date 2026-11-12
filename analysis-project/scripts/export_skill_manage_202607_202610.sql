-- 导出 3 名人员 2026 年 7-10 月的个人 Skill 数据。
-- 姓名、部门、统计组均关联人员台账获取；本 SQL 不写人员台账。
-- 全部导出列使用中文别名。

WITH ledger_ranked AS (
    SELECT
        p."统一认证号",
        p."姓名",
        p."部门",
        p."统计组",
        p."岗位角色",
        p."统计月份",
        ROW_NUMBER() OVER (
            PARTITION BY p."统一认证号"
            ORDER BY p."统计月份" DESC, p.id DESC
        ) AS rn
    FROM developer_pl_person_info_t p
    WHERE p."统一认证号" IN ('555183084', '555079102', '555033645')
      AND p."有效日期截止" = '9999-12-31'
),
latest_ledger AS (
    SELECT
        "统一认证号",
        "姓名",
        "部门",
        "统计组",
        "岗位角色",
        "统计月份"
    FROM ledger_ranked
    WHERE rn = 1
)
SELECT
    s.id                                             AS "Skill编号",
    p."姓名"                                        AS "姓名",
    s.owner_user_id                                  AS "统一认证号",
    p."部门"                                        AS "部门",
    p."统计组"                                      AS "统计组",
    p."岗位角色"                                    AS "岗位角色",
    p."统计月份"                                    AS "人员台账统计月份",
    s.name                                           AS "Skill名称",
    s.description                                    AS "Skill描述",
    s.content                                        AS "Skill内容",
    s.category                                       AS "Skill分类",
    s.tags                                           AS "Skill标签",
    s.status                                         AS "Skill状态",
    CASE s.status
        WHEN 'ACTIVE' THEN '已启用'
        WHEN 'INACTIVE' THEN '未启用'
        ELSE s.status
    END                                              AS "Skill状态中文",
    s.visibility                                     AS "可见性",
    CASE s.visibility
        WHEN 'PERSONAL' THEN '个人'
        WHEN 'PRIVATE' THEN '私有'
        WHEN 'PUBLIC' THEN '公开'
        ELSE s.visibility
    END                                              AS "可见性中文",
    s.like_count                                     AS "点赞数",
    TO_CHAR(s.created_at, 'YYYY-MM')                 AS "所属月份",
    s.created_at                                     AS "创建时间",
    s.updated_at                                     AS "更新时间"
FROM skill_manage s
JOIN latest_ledger p
  ON p."统一认证号" = s.owner_user_id
WHERE s.owner_user_id IN ('555183084', '555079102', '555033645')
  AND s.created_at >= TIMESTAMP '2026-07-01 00:00:00'
  AND s.created_at <  TIMESTAMP '2026-11-01 00:00:00'
  AND s.status = 'INACTIVE'
  AND s.visibility = 'PERSONAL'
  AND s.deleted_at IS NULL
ORDER BY s.owner_user_id, s.created_at, s.id;
