INSERT INTO ai_chat_runtime_config (config_key, config_value, config_description)
SELECT 'notification_contact_html', '', '通知邮件联系人 HTML 片段；为空则不追加联系人'
WHERE NOT EXISTS (SELECT 1 FROM ai_chat_runtime_config WHERE config_key = 'notification_contact_html');

UPDATE ai_chat_runtime_config
SET config_value = '<div style="margin-top: 60px;">
  <div style="float: left; font-size: 14px;">
    联系人：邓帅锋<br>
    联系方式：15179408378
  </div>
</div>',
    config_description = '通知邮件联系人 HTML 片段；为空则不追加联系人'
WHERE config_key = 'notification_contact_html';