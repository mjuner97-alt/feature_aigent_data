-- 开发环境人员台账初始化与模拟数据。请在 gauss-common 公共库执行。
-- 字段沿用原始台账定义；字符串显式指定长度，兼容 openGauss。
-- 模拟数据以 linked_id='codex_demo_ledger_*' 标记，重复执行不重复插入。
BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname=current_schema() AND c.relname='seq_developer_pl_person_info_t_id') THEN
        CREATE SEQUENCE seq_developer_pl_person_info_t_id;
    END IF;
END $$;
CREATE TABLE IF NOT EXISTS developer_pl_person_info_t (
    "类型" VARCHAR(1000),
    "统计月份" VARCHAR(1000),
    "部门" VARCHAR(1000),
    "应用" VARCHAR(1000),
    "产品线" VARCHAR(1000),
    "姓名" VARCHAR(1000),
    "统一认证号" VARCHAR(1000),
    "所属组" VARCHAR(1000),
    "考核人数" VARCHAR(1000),
    "adlm组" VARCHAR(1000),
    "三部处理组" VARCHAR(1000),
    "四部处理组" VARCHAR(1000),
    "统计组" VARCHAR(1000),
    "季度" VARCHAR(1000),
    "版本月份" VARCHAR(1000),
    "是否统计" VARCHAR(1000),
    "产品部测试人员对应开发部门" VARCHAR(1000),
    "产品部测试人员对应开发行政小组" VARCHAR(1000),
    "岗位角色" VARCHAR(1000),
    "更新时间" VARCHAR(1000),
    "总行批复部门" VARCHAR(1000),
    "所属小团队" VARCHAR(1000),
    "产品部测试人员对应统计组" VARCHAR(1000),
    "人员分类" VARCHAR(1000),
    "备注" VARCHAR(1000),
    "实际行政组" VARCHAR(1000),
    "测试大组" VARCHAR(1000),
    "更新人" VARCHAR(1000),
    id NUMERIC DEFAULT nextval('seq_developer_pl_person_info_t_id'::regclass) NOT NULL
        CONSTRAINT developer_pl_person_info_t_pkey PRIMARY KEY,
    "备注_rms" VARCHAR(1000),
    linked_id VARCHAR(100),
    "是否设计骨干" VARCHAR(100),
    "是否摊分" VARCHAR(1000),
    "参与比例" VARCHAR(1000),
    "应用分层" VARCHAR(1000),
    "是否第五层" VARCHAR(1000),
    "进入中心时间" VARCHAR(1000),
    "有效日期开始" VARCHAR(1000),
    "有效日期截止" VARCHAR(1000),
    "当月投入权重" VARCHAR(1000),
    "实际投入人数" VARCHAR(1000),
    "办公场地" VARCHAR(1000),
    "分管领导姓名" VARCHAR(1000),
    "分管领导统一认证号" VARCHAR(1000)
);
COMMENT ON TABLE developer_pl_person_info_t IS '人员台账：团队配置在最新统计月份按认证号去重，优先选择截止日期为9999-12-31的记录';
COMMENT ON COLUMN developer_pl_person_info_t."产品部测试人员对应开发部门" IS '测试/产品经理对口开发部门';
COMMENT ON COLUMN developer_pl_person_info_t."产品部测试人员对应统计组" IS '测试/产品经理对口开发部门统计组';
COMMENT ON COLUMN developer_pl_person_info_t."办公场地" IS '园区';
COMMENT ON COLUMN developer_pl_person_info_t."统计组" IS '团队名称';

-- 使用目录检查建索引，兼容不支持 CREATE INDEX IF NOT EXISTS 的版本。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname=current_schema() AND indexname='idx_pl_perosn_t_unserno') THEN
        CREATE INDEX idx_pl_perosn_t_unserno ON developer_pl_person_info_t ("统一认证号", "统计月份");
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname=current_schema() AND indexname='idx_pl_perosn_t_ver') THEN
        CREATE INDEX idx_pl_perosn_t_ver ON developer_pl_person_info_t ("版本月份");
    END IF;
END $$;

-- 3个部门 × 每部门3个团队 × 每团队4人，共36名有效人员。
-- 每个团队都有“模拟张三”，用于验证同名人员通过认证号、部门、团队区分。
INSERT INTO developer_pl_person_info_t (
    "类型","统计月份","部门","应用","产品线","姓名","统一认证号",
    "所属组","统计组","季度","版本月份","是否统计","岗位角色",
    "更新时间","所属小团队","人员分类","备注","更新人",linked_id,
    "进入中心时间","有效日期开始","有效日期截止","当月投入权重","实际投入人数",
    "办公场地","分管领导姓名","分管领导统一认证号"
)
SELECT '模拟数据','202611',d.dept,'模拟应用' || t.n,'模拟产品线' || d.n,
    CASE p.n WHEN 1 THEN '模拟张三' WHEN 2 THEN '模拟李四' WHEN 3 THEN '模拟王五' ELSE '模拟赵六' END,
    'codex_demo_user_' || d.n || t.n || p.n,
    d.dept || '-行政组',d.dept || '-' || t.team,'2026Q4','202611','是',
    CASE p.n WHEN 1 THEN '项目经理' WHEN 2 THEN '开发工程师' WHEN 3 THEN '测试工程师' ELSE '分管领导' END,
    '2026-11-10 10:00:00',d.dept || '-' || t.team,'内部人员','团队配置联调模拟数据','codex_demo',
    'codex_demo_ledger_current_' || d.n || t.n || p.n,
    '2024-01-01','2026-11-01','9999-12-31','1','1','模拟园区' || d.n,
    '模拟赵六','codex_demo_user_' || d.n || t.n || '4'
FROM (VALUES (1,'平台研发部'),(2,'数据产品部'),(3,'质量保障部')) AS d(n,dept)
CROSS JOIN (VALUES (1,'基础平台组'),(2,'应用研发组'),(3,'质量测试组')) AS t(n,team)
CROSS JOIN (VALUES (1),(2),(3),(4)) AS p(n)
WHERE NOT EXISTS (
    SELECT 1 FROM developer_pl_person_info_t old
    WHERE old.linked_id='codex_demo_ledger_current_' || d.n || t.n || p.n
);

-- 4条边界记录：上月同人、同月已失效、同月重复有效、仅上月存在。
INSERT INTO developer_pl_person_info_t (
    "类型","统计月份","部门","统计组","姓名","统一认证号","岗位角色",
    "版本月份","有效日期开始","有效日期截止","备注","更新人",linked_id
)
SELECT '模拟数据',x.month,x.dept,x.team,x.name,x.user_id,'开发工程师',x.month,
       x.start_date,x.end_date,x.remark,'codex_demo',x.fixture_id
FROM (VALUES
    ('202610','平台研发部','平台研发部-历史团队','模拟张三','codex_demo_user_111','2026-10-01','9999-12-31','上月同人，不应在最新月份出现','codex_demo_ledger_previous'),
    ('202611','平台研发部','平台研发部-已失效团队','模拟张三','codex_demo_user_111','2026-11-01','2026-11-09','同月失效，不应作为当前人员归属','codex_demo_ledger_expired'),
    ('202611','平台研发部','平台研发部-基础平台组','模拟张三','codex_demo_user_111','2026-11-10','9999-12-31','同月有效重复，查询按认证号去重','codex_demo_ledger_duplicate'),
    ('202610','质量保障部','质量保障部-历史团队','模拟历史人员','codex_demo_history_only','2026-10-01','9999-12-31','只存在于上月，不应在候选列表出现','codex_demo_ledger_history_only')
) AS x(month,dept,team,name,user_id,start_date,end_date,remark,fixture_id)
WHERE NOT EXISTS (SELECT 1 FROM developer_pl_person_info_t old WHERE old.linked_id=x.fixture_id);

-- 120 人各有普通日期和 9999 日期，另 60 人各有两条普通日期。
-- 本批共 360 条原始记录，去重后应为 180 人。
INSERT INTO developer_pl_person_info_t (
    "类型","统计月份","部门","统计组","姓名","统一认证号","岗位角色",
    "版本月份","有效日期截止",linked_id
)
SELECT '模拟数据', '202611', '模拟有效期核对部',
       CASE WHEN n <= 120 THEN '模拟混合截止组' ELSE '模拟普通截止组' END,
       '模拟有效期人员' || n, 'codex_demo_expiry_' || n, '开发工程师',
       '202611',
       CASE WHEN variant = 1 AND n <= 120 THEN '9999-12-31'
            WHEN variant = 1 THEN '2026-11-30' ELSE '2026-11-12' END,
       'codex_demo_ledger_expiry_' || n || '_' || variant
FROM generate_series(1, 180) AS people(n)
CROSS JOIN (VALUES (1), (2)) AS variants(variant)
WHERE NOT EXISTS (
    SELECT 1 FROM developer_pl_person_info_t old
    WHERE old.linked_id = 'codex_demo_ledger_expiry_' || n || '_' || variant
);

COMMIT;
