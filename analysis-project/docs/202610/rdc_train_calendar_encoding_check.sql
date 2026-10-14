-- 编码校验：'2026年9月份版本' 合法 UTF-8 应为 9 字符 / 19 字节
SELECT 版本计划, LENGTH(版本计划) AS char_len, OCTET_LENGTH(版本计划) AS byte_len,
       COUNT(*) AS cnt
FROM rdc_train_calendar
GROUP BY 版本计划
ORDER BY 版本计划
