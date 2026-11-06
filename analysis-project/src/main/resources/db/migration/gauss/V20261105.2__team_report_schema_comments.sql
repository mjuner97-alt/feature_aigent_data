-- 在 V20261105.1 之后执行，仅更新元数据，不修改业务数据。
COMMENT ON TABLE team_report_department IS '报告配置部门字典；用于首次选择部门及限定团队范围';
COMMENT ON COLUMN team_report_department.id IS '部门唯一标识';
COMMENT ON COLUMN team_report_department.name IS '部门完整名称；作为公共参数 dev_dept 的值';
COMMENT ON COLUMN team_report_department.enabled IS '是否启用；控制新增配置时的部门候选';

COMMENT ON TABLE team_report_team IS '报告配置团队字典；每个团队归属一个部门';
COMMENT ON COLUMN team_report_team.id IS '团队唯一标识';
COMMENT ON COLUMN team_report_team.department_id IS '所属部门标识；关联 team_report_department.id';
COMMENT ON COLUMN team_report_team.name IS '团队完整名称；作为公共参数 stat_group 的值';
COMMENT ON COLUMN team_report_team.sort_order IS '团队候选显示顺序；数值越小越靠前';
COMMENT ON COLUMN team_report_team.enabled IS '是否启用；控制首次回显及手动新增时的团队候选';

COMMENT ON TABLE team_report_notify_person IS '报告默认通知人员参数表；按部门和团队提供首次配置的收件人及抄送人';
COMMENT ON COLUMN team_report_notify_person.id IS '通知人员配置主键；自动生成';
COMMENT ON COLUMN team_report_notify_person.dev_dept IS '部门完整名称；与部门字典名称一致';
COMMENT ON COLUMN team_report_notify_person.stat_group IS '团队完整名称；部门报告使用空字符串';
COMMENT ON COLUMN team_report_notify_person.user_id IS '人员认证账号；供现有组织服务校验和通知解析';
COMMENT ON COLUMN team_report_notify_person.user_name IS '人员姓名';
COMMENT ON COLUMN team_report_notify_person.position_role IS '岗位或角色；用于说明人员职责';
COMMENT ON COLUMN team_report_notify_person.recipient_type IS '通知类型：TO 为收件人，CC 为抄送人';
COMMENT ON COLUMN team_report_notify_person.sort_order IS '默认通知名单显示顺序；数值越小越靠前';
COMMENT ON COLUMN team_report_notify_person.enabled IS '是否启用该默认通知人员配置';
COMMENT ON COLUMN team_report_notify_person.remark IS '配置备注';
COMMENT ON COLUMN team_report_notify_person.created_at IS '配置创建时间';
COMMENT ON COLUMN team_report_notify_person.updated_at IS '配置最后更新时间；修改配置时由写入方维护';

COMMENT ON TABLE skill_flow_report_param IS '长任务报告参数池；一行代表一份报告的执行配置；部门报告 team_id 为空字符串';
COMMENT ON COLUMN skill_flow_report_param.id IS '报告参数池行主键；编辑保留原行标识';
COMMENT ON COLUMN skill_flow_report_param.flow_id IS '所属长任务标识；关联 skill_flow.id';
COMMENT ON COLUMN skill_flow_report_param.team_id IS '所属团队标识；部门报告为空字符串；同一任务内唯一';
COMMENT ON COLUMN skill_flow_report_param.params_json IS '公共参数 JSON：report_type、dev_dept、stat_group；data_date 使用执行日期，不重复存储';
COMMENT ON COLUMN skill_flow_report_param.keyword IS '报告触发关键词；初始为部门名或团队名加报告类型，可手动修改';
COMMENT ON COLUMN skill_flow_report_param.auto_enabled IS '是否参与自动执行；手动执行可独立选择该行';
COMMENT ON COLUMN skill_flow_report_param.sort_order IS '参数池执行顺序；数值越小越先执行，同值按主键排序';
COMMENT ON COLUMN skill_flow_report_param.to_receivers_json IS '保存的收件人账号 JSON 数组；空数组允许生成报告并暂停邮件通知';
COMMENT ON COLUMN skill_flow_report_param.cc_receivers_json IS '保存的抄送人账号 JSON 数组；可为空数组';
COMMENT ON COLUMN skill_flow_report_param.created_at IS '参数池行创建时间';
COMMENT ON COLUMN skill_flow_report_param.updated_at IS '参数池行最后更新时间';

COMMENT ON COLUMN skill_flow.team_report_json IS '报告配置头 JSON：报告模式和固定部门；旧版 targets 在首次执行或编辑时事务性迁入参数池；NULL 保留原执行逻辑';
COMMENT ON COLUMN skill_flow.legacy_queue_type_id IS '历史任务重新归类前的类型标识；保留原队列调度语义';
COMMENT ON COLUMN skill_flow_execution.type_id IS '提交执行时保存的任务类型标识；用于历史展示及类型筛选';
COMMENT ON COLUMN skill_flow_execution.batch_id IS '同一轮报告执行批次标识；关联本轮各对象的独立执行记录';
COMMENT ON COLUMN skill_flow_execution.team_id IS '本次执行团队标识；部门报告和原有执行可为空';
COMMENT ON COLUMN skill_flow_execution.public_params_json IS '提交时冻结的报告公共参数 JSON；部门、团队及报告类型等信息不受后续配置修改影响，日期沿用 data_date';
