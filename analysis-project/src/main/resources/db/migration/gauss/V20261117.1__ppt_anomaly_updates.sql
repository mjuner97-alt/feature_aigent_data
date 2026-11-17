CREATE TABLE IF NOT EXISTS digital_qa_ppt_material_batch (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS digital_qa_ppt_material_version (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES digital_qa_ppt_material_batch(id),
    version_no INTEGER NOT NULL,
    version_name VARCHAR(128) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    file_path VARCHAR(2048) NOT NULL,
    file_sha256 VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
    uploaded_by VARCHAR(128) NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (batch_id, version_no),
    UNIQUE (batch_id, file_sha256)
);

CREATE TABLE IF NOT EXISTS digital_qa_ppt_slide (
    id BIGSERIAL PRIMARY KEY,
    version_id BIGINT NOT NULL REFERENCES digital_qa_ppt_material_version(id),
    page_no INTEGER NOT NULL,
    title VARCHAR(512),
    extracted_json TEXT NOT NULL,
    image_path VARCHAR(2048),
    render_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    recognition_type VARCHAR(64),
    parse_status VARCHAR(32) NOT NULL DEFAULT 'PARSED',
    UNIQUE (version_id, page_no)
);

CREATE TABLE IF NOT EXISTS digital_qa_ppt_finding (
    id BIGSERIAL PRIMARY KEY,
    version_id BIGINT NOT NULL REFERENCES digital_qa_ppt_material_version(id),
    slide_id BIGINT NOT NULL REFERENCES digital_qa_ppt_slide(id),
    sequence_no INTEGER NOT NULL,
    chapter VARCHAR(512),
    title VARCHAR(512),
    region_name VARCHAR(256),
    table_no INTEGER,
    row_no INTEGER,
    source_text TEXT NOT NULL,
    anomaly_keyword VARCHAR(512),
    negation_keyword VARCHAR(512),
    auto_departments TEXT NOT NULL DEFAULT '[]',
    owner_user_id VARCHAR(128),
    status VARCHAR(64) NOT NULL DEFAULT 'PENDING_FEEDBACK',
    content_fingerprint VARCHAR(128) NOT NULL,
    version_relation VARCHAR(32) NOT NULL DEFAULT 'NEW',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (version_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS digital_qa_ppt_finding_feedback (
    id BIGSERIAL PRIMARY KEY,
    finding_id BIGINT NOT NULL REFERENCES digital_qa_ppt_finding(id),
    judgement VARCHAR(32) NOT NULL,
    adjusted_departments TEXT NOT NULL DEFAULT '[]',
    explanation TEXT,
    user_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS digital_qa_ppt_detail_link (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES digital_qa_ppt_material_batch(id),
    alias_name VARCHAR(255) NOT NULL,
    target_url VARCHAR(2048) NOT NULL,
    ppt_title VARCHAR(512) NOT NULL,
    chapter VARCHAR(512),
    page_no INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS digital_qa_ppt_mail_record (
    id BIGSERIAL PRIMARY KEY,
    version_id BIGINT NOT NULL REFERENCES digital_qa_ppt_material_version(id),
    department VARCHAR(255) NOT NULL,
    subject VARCHAR(512) NOT NULL,
    body TEXT NOT NULL,
    to_users TEXT NOT NULL DEFAULT '[]',
    cc_users TEXT NOT NULL DEFAULT '[]',
    status VARCHAR(32) NOT NULL DEFAULT 'PREVIEWED',
    sent_by VARCHAR(128) NOT NULL,
    sent_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS digital_qa_ppt_idx_version_batch ON digital_qa_ppt_material_version(batch_id, version_no);
CREATE INDEX IF NOT EXISTS digital_qa_ppt_idx_slide_version ON digital_qa_ppt_slide(version_id, page_no);
CREATE INDEX IF NOT EXISTS digital_qa_ppt_idx_finding_version_department ON digital_qa_ppt_finding(version_id, status);
CREATE INDEX IF NOT EXISTS digital_qa_ppt_idx_finding_owner ON digital_qa_ppt_finding(owner_user_id, status);

COMMENT ON TABLE digital_qa_ppt_material_batch IS 'PPT 异常分拣材料批次，承载同一组材料的多个稿件版本';
COMMENT ON COLUMN digital_qa_ppt_material_batch.id IS '材料批次主键';
COMMENT ON COLUMN digital_qa_ppt_material_batch.name IS '材料批次名称';
COMMENT ON COLUMN digital_qa_ppt_material_batch.created_by IS '批次创建人用户 ID';
COMMENT ON COLUMN digital_qa_ppt_material_batch.created_at IS '批次创建时间';
COMMENT ON COLUMN digital_qa_ppt_material_batch.updated_at IS '批次最后更新时间';

COMMENT ON TABLE digital_qa_ppt_material_version IS 'PPT 异常分拣稿件版本，按材料批次内上传顺序递增';
COMMENT ON COLUMN digital_qa_ppt_material_version.id IS '稿件版本主键';
COMMENT ON COLUMN digital_qa_ppt_material_version.batch_id IS '所属材料批次 ID';
COMMENT ON COLUMN digital_qa_ppt_material_version.version_no IS '批次内版本序号，1 表示初稿';
COMMENT ON COLUMN digital_qa_ppt_material_version.version_name IS '版本显示名称，例如初稿、第二版或终稿';
COMMENT ON COLUMN digital_qa_ppt_material_version.file_name IS '上传时的原始 PPT 文件名';
COMMENT ON COLUMN digital_qa_ppt_material_version.file_path IS '服务器保存的 PPT 文件路径';
COMMENT ON COLUMN digital_qa_ppt_material_version.file_sha256 IS '上传文件 SHA-256 哈希，用于批次内重复文件校验';
COMMENT ON COLUMN digital_qa_ppt_material_version.status IS '版本状态，例如 UPLOADED、ANALYZED';
COMMENT ON COLUMN digital_qa_ppt_material_version.uploaded_by IS '版本上传人用户 ID';
COMMENT ON COLUMN digital_qa_ppt_material_version.uploaded_at IS '版本上传时间';

COMMENT ON TABLE digital_qa_ppt_slide IS 'PPT 版本中的页面解析结果和页面渲染信息';
COMMENT ON COLUMN digital_qa_ppt_slide.id IS 'PPT 页面主键';
COMMENT ON COLUMN digital_qa_ppt_slide.version_id IS '所属稿件版本 ID';
COMMENT ON COLUMN digital_qa_ppt_slide.page_no IS 'PPT 页码，从 1 开始';
COMMENT ON COLUMN digital_qa_ppt_slide.title IS '页面标题或页面提取到的第一段标题文本';
COMMENT ON COLUMN digital_qa_ppt_slide.extracted_json IS '页面文字框和表格内容的 JSON 原文';
COMMENT ON COLUMN digital_qa_ppt_slide.image_path IS '整页 PPT 渲染图片路径';
COMMENT ON COLUMN digital_qa_ppt_slide.render_status IS '页面渲染状态，例如 PENDING、READY、UNAVAILABLE';
COMMENT ON COLUMN digital_qa_ppt_slide.recognition_type IS '页面识别类型';
COMMENT ON COLUMN digital_qa_ppt_slide.parse_status IS '页面解析状态';

COMMENT ON TABLE digital_qa_ppt_finding IS 'PPT 异常识别和部门分拣结果';
COMMENT ON COLUMN digital_qa_ppt_finding.id IS '异常结果主键';
COMMENT ON COLUMN digital_qa_ppt_finding.version_id IS '所属稿件版本 ID';
COMMENT ON COLUMN digital_qa_ppt_finding.slide_id IS '异常所在 PPT 页面 ID';
COMMENT ON COLUMN digital_qa_ppt_finding.sequence_no IS '当前版本异常结果序号';
COMMENT ON COLUMN digital_qa_ppt_finding.chapter IS '目录章节';
COMMENT ON COLUMN digital_qa_ppt_finding.title IS '异常所属页面或章节标题';
COMMENT ON COLUMN digital_qa_ppt_finding.region_name IS '异常所在页面区域名称';
COMMENT ON COLUMN digital_qa_ppt_finding.table_no IS '异常所在表格编号';
COMMENT ON COLUMN digital_qa_ppt_finding.row_no IS '异常所在表格行号';
COMMENT ON COLUMN digital_qa_ppt_finding.source_text IS '命中规则的 PPT 原始文本';
COMMENT ON COLUMN digital_qa_ppt_finding.anomaly_keyword IS '命中的异常关键词';
COMMENT ON COLUMN digital_qa_ppt_finding.negation_keyword IS '命中的否定或消除关键词';
COMMENT ON COLUMN digital_qa_ppt_finding.auto_departments IS '系统自动分拣的部门 JSON 数组';
COMMENT ON COLUMN digital_qa_ppt_finding.owner_user_id IS '当前异常板块负责人用户 ID';
COMMENT ON COLUMN digital_qa_ppt_finding.status IS '异常反馈状态，例如待反馈、已确认、已调整或未归属';
COMMENT ON COLUMN digital_qa_ppt_finding.content_fingerprint IS '用于跨版本匹配的内容指纹';
COMMENT ON COLUMN digital_qa_ppt_finding.version_relation IS '与上一版本的关系，例如 NEW、CONTINUED、CHANGED';
COMMENT ON COLUMN digital_qa_ppt_finding.created_at IS '异常结果生成时间';

COMMENT ON TABLE digital_qa_ppt_finding_feedback IS '异常结果的人工反馈和调整历史';
COMMENT ON COLUMN digital_qa_ppt_finding_feedback.id IS '反馈记录主键';
COMMENT ON COLUMN digital_qa_ppt_finding_feedback.finding_id IS '关联异常结果 ID';
COMMENT ON COLUMN digital_qa_ppt_finding_feedback.judgement IS '人工判定，例如确认异常、非异常或待确认';
COMMENT ON COLUMN digital_qa_ppt_finding_feedback.adjusted_departments IS '人工调整后的部门 JSON 数组';
COMMENT ON COLUMN digital_qa_ppt_finding_feedback.explanation IS '反馈原因、部门调整说明或补充说明';
COMMENT ON COLUMN digital_qa_ppt_finding_feedback.user_id IS '反馈提交人用户 ID';
COMMENT ON COLUMN digital_qa_ppt_finding_feedback.created_at IS '反馈提交时间';

COMMENT ON TABLE digital_qa_ppt_detail_link IS 'PPT 标题对应的明细云文档链接，页面和邮件展示别名';
COMMENT ON COLUMN digital_qa_ppt_detail_link.id IS '明细链接主键';
COMMENT ON COLUMN digital_qa_ppt_detail_link.batch_id IS '所属材料批次 ID';
COMMENT ON COLUMN digital_qa_ppt_detail_link.alias_name IS '页面和邮件展示的明细别名';
COMMENT ON COLUMN digital_qa_ppt_detail_link.target_url IS '实际明细云文档地址';
COMMENT ON COLUMN digital_qa_ppt_detail_link.ppt_title IS '对应 PPT 标题';
COMMENT ON COLUMN digital_qa_ppt_detail_link.chapter IS '对应目录章节';
COMMENT ON COLUMN digital_qa_ppt_detail_link.page_no IS '对应 PPT 页码';
COMMENT ON COLUMN digital_qa_ppt_detail_link.enabled IS '明细链接是否启用';
COMMENT ON COLUMN digital_qa_ppt_detail_link.created_by IS '明细链接创建人用户 ID';
COMMENT ON COLUMN digital_qa_ppt_detail_link.created_at IS '明细链接创建时间';
COMMENT ON COLUMN digital_qa_ppt_detail_link.updated_at IS '明细链接最后更新时间';

COMMENT ON TABLE digital_qa_ppt_mail_record IS 'PPT 异常分拣邮件预览、发送和收件人快照记录';
COMMENT ON COLUMN digital_qa_ppt_mail_record.id IS '邮件记录主键';
COMMENT ON COLUMN digital_qa_ppt_mail_record.version_id IS '所属稿件版本 ID';
COMMENT ON COLUMN digital_qa_ppt_mail_record.department IS '本次邮件对应的部门';
COMMENT ON COLUMN digital_qa_ppt_mail_record.subject IS '邮件主题';
COMMENT ON COLUMN digital_qa_ppt_mail_record.body IS '邮件正文 HTML 或文本内容';
COMMENT ON COLUMN digital_qa_ppt_mail_record.to_users IS '主送人员用户 ID JSON 数组';
COMMENT ON COLUMN digital_qa_ppt_mail_record.cc_users IS '抄送人员用户 ID JSON 数组';
COMMENT ON COLUMN digital_qa_ppt_mail_record.status IS '邮件状态，例如 PREVIEWED、SENT 或 FAILED';
COMMENT ON COLUMN digital_qa_ppt_mail_record.sent_by IS '邮件操作人用户 ID';
COMMENT ON COLUMN digital_qa_ppt_mail_record.sent_at IS '邮件实际发送时间';
COMMENT ON COLUMN digital_qa_ppt_mail_record.created_at IS '邮件记录创建时间';

CREATE TABLE digital_qa_ppt_keyword_config (
    id BIGSERIAL PRIMARY KEY,
    kind VARCHAR(32) NOT NULL,
    name VARCHAR(512) NOT NULL,
    target VARCHAR(2048) NOT NULL,
    upper_limit_ratio DOUBLE PRECISION,
    first_row INTEGER,
    last_row INTEGER,
    UNIQUE (kind, name)
);

COMMENT ON TABLE digital_qa_ppt_keyword_config IS '例会材料识别关键词配置';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.id IS '配置记录主键';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.kind IS '配置类型：部门关键词、页面范围、标题与板块或板块联系人';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.name IS '匹配键：部门关键词、页面标题或板块名称';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.target IS '映射目标：部门、识别模式、板块或以逗号分隔的联系人';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.upper_limit_ratio IS '上方表格分界线占页高的比例';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.first_row IS '参与识别的表格起始行，从 1 开始';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.last_row IS '参与识别的表格结束行，包含该行';

ALTER TABLE digital_qa_ppt_material_version
    DROP CONSTRAINT IF EXISTS digital_qa_ppt_material_version_batch_id_file_sha256_key;

INSERT INTO digital_qa_ppt_material_batch (name, created_by)
SELECT '例会材料', 'system'
WHERE NOT EXISTS (SELECT 1 FROM digital_qa_ppt_material_batch WHERE name = '例会材料');

UPDATE digital_qa_ppt_keyword_config
SET target = 'SKIP', upper_limit_ratio = NULL, first_row = NULL, last_row = NULL
WHERE kind = 'scope' AND target = 'REVIEW';

UPDATE digital_qa_ppt_slide
SET recognition_type = 'SKIP'
WHERE recognition_type = 'REVIEW';

ALTER TABLE digital_qa_ppt_slide ADD COLUMN assigned_section VARCHAR(512);
ALTER TABLE digital_qa_ppt_slide ADD COLUMN assigned_owner VARCHAR(255);
ALTER TABLE digital_qa_ppt_slide ADD COLUMN assignment_skipped BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE digital_qa_ppt_keyword_config ADD COLUMN created_at TIMESTAMP;
ALTER TABLE digital_qa_ppt_keyword_config ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE digital_qa_ppt_keyword_config ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

UPDATE digital_qa_ppt_keyword_config
SET created_at = COALESCE(created_at, updated_at, CURRENT_TIMESTAMP),
    updated_at = COALESCE(updated_at, CURRENT_TIMESTAMP)
WHERE created_at IS NULL OR updated_at IS NULL;

ALTER TABLE digital_qa_ppt_material_version ADD COLUMN analyzed_at TIMESTAMP;

CREATE TABLE digital_qa_ppt_feedback_notice (
    version_id BIGINT NOT NULL REFERENCES digital_qa_ppt_material_version(id),
    finding_id BIGINT NOT NULL REFERENCES digital_qa_ppt_finding(id),
    recipient VARCHAR(255) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    PRIMARY KEY (version_id, finding_id, recipient, kind)
);

ALTER TABLE digital_qa_ppt_feedback_notice ADD COLUMN subject VARCHAR(255);
ALTER TABLE digital_qa_ppt_feedback_notice ADD COLUMN body TEXT;

COMMENT ON COLUMN digital_qa_ppt_material_version.file_sha256 IS '上传文件的 SHA-256 哈希';

ALTER TABLE digital_qa_ppt_feedback_notice ADD COLUMN notified_at TIMESTAMP;
ALTER TABLE digital_qa_ppt_feedback_notice ALTER COLUMN notified_at SET DEFAULT CURRENT_TIMESTAMP;

-- 配置每个标题在各部门的经办人。同一标题和部门可配置多位经办人。
CREATE TABLE digital_qa_ppt_title_handler (
    title VARCHAR(512) NOT NULL,
    department VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (title, department, user_id)
);
COMMENT ON TABLE digital_qa_ppt_title_handler IS '例会材料标题经办人配置表，按页面标题和部门维护一位或多位经办人';
COMMENT ON COLUMN digital_qa_ppt_title_handler.title IS '标题配置中的页面标题';
COMMENT ON COLUMN digital_qa_ppt_title_handler.department IS '经办人对应的部门，用于匹配识别出的异常部门';
COMMENT ON COLUMN digital_qa_ppt_title_handler.user_id IS '经办人的用户 ID，用于通知和分配反馈权限';

-- 新识别的异常按部门保存。同一部门的多位经办人共用该条异常的反馈状态。
ALTER TABLE digital_qa_ppt_finding ADD COLUMN feedback_department VARCHAR(255);
COMMENT ON COLUMN digital_qa_ppt_finding.feedback_department IS '异常对应的反馈部门，迁移前的历史异常留空';

-- 保存每条异常当时分配的经办人，避免标题配置变化影响已分配的反馈事项。
CREATE TABLE digital_qa_ppt_finding_handler (
    finding_id BIGINT NOT NULL REFERENCES digital_qa_ppt_finding(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (finding_id, user_id)
);
COMMENT ON TABLE digital_qa_ppt_finding_handler IS '例会材料异常经办人分配表，记录每条异常的经办人，用于通知和反馈权限校验';
COMMENT ON COLUMN digital_qa_ppt_finding_handler.finding_id IS '异常记录 ID，同一异常下的经办人共用反馈状态';
COMMENT ON COLUMN digital_qa_ppt_finding_handler.user_id IS '已分配经办人的用户 ID';

-- 按用户查询待反馈事项时使用此索引。
CREATE INDEX idx_ppt_finding_handler_user ON digital_qa_ppt_finding_handler(user_id, finding_id);

-- 汇总历史异常的单一负责人和新异常的多位经办人，供通知、权限和反馈记录查询。
CREATE VIEW digital_qa_ppt_finding_recipient AS
SELECT id AS finding_id, owner_user_id AS user_id
FROM digital_qa_ppt_finding WHERE owner_user_id IS NOT NULL
UNION
SELECT finding_id, user_id FROM digital_qa_ppt_finding_handler;

COMMENT ON VIEW digital_qa_ppt_finding_recipient IS '例会材料异常接收人，包含历史负责人和新分配的经办人';
COMMENT ON COLUMN digital_qa_ppt_finding_recipient.finding_id IS '异常记录 ID';
COMMENT ON COLUMN digital_qa_ppt_finding_recipient.user_id IS '收到通知并可提交反馈的用户 ID';

UPDATE digital_qa_ppt_keyword_config SET updated_at=COALESCE(created_at,CURRENT_TIMESTAMP) WHERE updated_at IS NULL;
ALTER TABLE digital_qa_ppt_keyword_config ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
COMMENT ON COLUMN digital_qa_ppt_keyword_config.updated_at IS '配置最近一次编辑的时间，新建时记录首次保存时间';

-- 每个版本优先继承同批次中最近一次有反馈的同标题事项。
CREATE VIEW digital_qa_ppt_feedback_to_inherit AS
WITH inherit_findings AS (
    SELECT f.id, f.version_id, v.batch_id, v.version_no, v.status AS version_status,
           f.chapter, f.feedback_department,
           regexp_replace(
               regexp_replace(COALESCE(f.title, ''),
                   replace('^[~s　]*([0-9]+(([.．][0-9]+)+[.．、~s　]*|[.．、~s　]+))?', '~', chr(92)), ''),
               replace('[~s　]+$', '~', chr(92)), ''
           ) AS title_key
    FROM digital_qa_ppt_finding f
    JOIN digital_qa_ppt_material_version v ON v.id=f.version_id
), inherit_matches AS (
    SELECT f.id AS finding_id, f.version_id, p.id AS prior_id, p.version_id AS prior_version_id, p.version_no,
           DENSE_RANK() OVER (PARTITION BY f.id, p.version_id
               ORDER BY CASE WHEN f.chapter=p.chapter THEN 0 ELSE 1 END) AS preference
    FROM inherit_findings f
    JOIN inherit_findings p ON p.batch_id=f.batch_id AND p.version_no<f.version_no
        AND p.version_status='ANALYZED' AND p.title_key=f.title_key
        AND (f.feedback_department=p.feedback_department OR (f.feedback_department IS NULL AND p.feedback_department IS NULL)
             OR (p.feedback_department IS NULL AND EXISTS (
                 SELECT 1 FROM digital_qa_ppt_finding_recipient prior_recipient
                 JOIN digital_qa_ppt_finding_recipient current_recipient ON current_recipient.user_id=prior_recipient.user_id
                 WHERE prior_recipient.finding_id=p.id AND current_recipient.finding_id=f.id
             )))
    WHERE f.title_key <> ''
), inherit_candidates AS (
    SELECT inherit_matches.*,
           COUNT(*) OVER (PARTITION BY finding_id, prior_version_id) AS candidate_count,
           COUNT(*) OVER (PARTITION BY prior_id, version_id) AS destination_count
    FROM inherit_matches WHERE preference=1
), inherit_notes AS (
    SELECT c.finding_id, c.version_id, r.judgement, r.adjusted_departments, r.explanation, r.user_id, r.created_at,
           ROW_NUMBER() OVER (PARTITION BY c.finding_id ORDER BY c.version_no DESC, r.id DESC) AS priority
    FROM inherit_candidates c
    JOIN digital_qa_ppt_finding_feedback r ON r.finding_id=c.prior_id
    WHERE c.candidate_count=1 AND c.destination_count=1
      AND EXISTS (SELECT 1 FROM digital_qa_ppt_finding_recipient h WHERE h.finding_id=c.finding_id)
      AND NOT EXISTS (SELECT 1 FROM digital_qa_ppt_finding_feedback own WHERE own.finding_id=c.finding_id)
)
SELECT finding_id, version_id, judgement, adjusted_departments, explanation, user_id, created_at
FROM inherit_notes WHERE priority=1;

CREATE TABLE digital_qa_ppt_title_detail_link (
    title VARCHAR(512) NOT NULL,
    detail_no INTEGER NOT NULL,
    link_no INTEGER NOT NULL,
    detail_name VARCHAR(255) NOT NULL,
    link_name VARCHAR(255) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    PRIMARY KEY (title, detail_no, link_no)
);

COMMENT ON TABLE digital_qa_ppt_title_detail_link IS '例会材料标题明细链接配置表，每个标题可配置多组明细，每组明细可配置多个链接';
COMMENT ON COLUMN digital_qa_ppt_title_detail_link.title IS '标题配置中的页面标题';
COMMENT ON COLUMN digital_qa_ppt_title_detail_link.detail_no IS '明细组在同一标题下的显示顺序，从 0 开始';
COMMENT ON COLUMN digital_qa_ppt_title_detail_link.link_no IS '链接在同一明细组下的显示顺序，从 0 开始';
COMMENT ON COLUMN digital_qa_ppt_title_detail_link.detail_name IS '反馈详情中显示的明细组名称';
COMMENT ON COLUMN digital_qa_ppt_title_detail_link.link_name IS '反馈详情中显示的链接名称';
COMMENT ON COLUMN digital_qa_ppt_title_detail_link.url IS '链接的完整 HTTP 或 HTTPS 地址';

ALTER TABLE digital_qa_ppt_finding ADD COLUMN assignment_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE digital_qa_ppt_finding_feedback ADD COLUMN assignment_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE digital_qa_ppt_feedback_notice ADD COLUMN assignment_revision INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN digital_qa_ppt_finding.assignment_revision IS '事项当前的部门归属修订号，初始为 0，每次更换反馈部门时递增';
COMMENT ON COLUMN digital_qa_ppt_finding_feedback.assignment_revision IS '提交反馈时事项的部门归属修订号，用于区分转部门前后的反馈历史';
COMMENT ON COLUMN digital_qa_ppt_feedback_notice.assignment_revision IS '通知对应的部门归属修订号，用于按归属去重，待反馈通知取事项当前修订号，已消除通知固定为 0';

ALTER TABLE digital_qa_ppt_feedback_notice
    DROP CONSTRAINT digital_qa_ppt_feedback_notice_pkey;
ALTER TABLE digital_qa_ppt_feedback_notice
    ADD PRIMARY KEY (version_id, finding_id, recipient, kind, assignment_revision);

CREATE INDEX idx_ppt_feedback_finding_revision
    ON digital_qa_ppt_finding_feedback(finding_id, assignment_revision, id);

CREATE TABLE digital_qa_ppt_assignment_change (
    id BIGSERIAL PRIMARY KEY,
    finding_id BIGINT NOT NULL REFERENCES digital_qa_ppt_finding(id),
    assignment_revision INTEGER NOT NULL,
    previous_department VARCHAR(255),
    department VARCHAR(255) NOT NULL,
    previous_handlers TEXT NOT NULL,
    handlers TEXT NOT NULL,
    changed_by VARCHAR(128) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE digital_qa_ppt_assignment_change IS '例会材料事项归属调整记录，记录手动补建、部门变更和经办人变更';
COMMENT ON COLUMN digital_qa_ppt_assignment_change.id IS '归属调整记录主键';
COMMENT ON COLUMN digital_qa_ppt_assignment_change.finding_id IS '被调整或补建的例会材料事项 ID';
COMMENT ON COLUMN digital_qa_ppt_assignment_change.assignment_revision IS '调整后的部门归属修订号，仅更换经办人时沿用原修订号';
COMMENT ON COLUMN digital_qa_ppt_assignment_change.previous_department IS '调整前的反馈部门，补建事项或原归属不明确时为空';
COMMENT ON COLUMN digital_qa_ppt_assignment_change.department IS '调整后的反馈部门';
COMMENT ON COLUMN digital_qa_ppt_assignment_change.previous_handlers IS '调整前的经办人统一认证号 JSON 数组';
COMMENT ON COLUMN digital_qa_ppt_assignment_change.handlers IS '调整后的经办人统一认证号 JSON 数组';
COMMENT ON COLUMN digital_qa_ppt_assignment_change.changed_by IS '执行调整或补建的登录用户统一认证号';
COMMENT ON COLUMN digital_qa_ppt_assignment_change.changed_at IS '调整或补建记录写入时间';

CREATE INDEX idx_ppt_assignment_change_finding_revision
    ON digital_qa_ppt_assignment_change(finding_id, assignment_revision);

CREATE TABLE IF NOT EXISTS digital_qa_ppt_report_notification (
    department TEXT PRIMARY KEY,
    recipients_to TEXT NOT NULL,
    recipients_cc TEXT NOT NULL
);

COMMENT ON TABLE digital_qa_ppt_report_notification IS '例会材料报告通知配置，按部门保存详情页手动通知的默认名单';
COMMENT ON COLUMN digital_qa_ppt_report_notification.department IS '配置所属部门名称，每个部门一条配置';
COMMENT ON COLUMN digital_qa_ppt_report_notification.recipients_to IS '默认收件人（TO）的统一认证号，按顺序存为 JSON 数组';
COMMENT ON COLUMN digital_qa_ppt_report_notification.recipients_cc IS '默认抄送人（CC）的统一认证号，按顺序存为 JSON 数组';
