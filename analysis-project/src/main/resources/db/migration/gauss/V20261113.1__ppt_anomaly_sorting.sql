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



