/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.service;

import java.util.regex.Pattern;

/**
 * Markdown 表 -> 标准 CSV 转换器.
 *
 * <p>识别规则: 行首尾都是 {@code |} + 存在 {@code |---|} 分隔行 -> markdown 表.
 * 剥离规则: 非 {@code |...|} 行 (如 {@code [sql_registry_exec] sqlId=...} 头 /
 * {@code 共 N 行} 尾) 自动跳过, LLM 可把完整工具结果原样传 content.
 * 字段转义: 含逗号/引号/换行的字段用双引号包裹, 内部 {@code "} 转义为 {@code ""} (RFC 4180).
 *
 * <p>由 {@link DownloadContentService} 在 mimeType=text/csv 且 content 含 markdown 表时调用.
 */
public final class MarkdownTableConverter {

    /** 匹配 markdown 表分隔行, 如 |---|---| 或 |:---:|---|. */
    private static final Pattern SEPARATOR = Pattern.compile("^\\|[-:\\s|]+\\|$");

    private MarkdownTableConverter() {}

    /** 含 |---| 分隔行视为 markdown 表. */
    public static boolean isMarkdownTable(String s) {
        return s != null && s.lines().anyMatch(line -> SEPARATOR.matcher(line.trim()).matches());
    }

    /**
     * 把 markdown 表转标准 CSV. 非 {@code |...|} 行 (头尾说明) 自动剥离.
     *
     * @param md 含 markdown 表的文本 (可有头尾说明行, 自动跳过)
     * @return 标准 CSV 字符串 (表头 + 数据行, RFC 4180 转义)
     */
    public static String toCsv(String md) {
        StringBuilder out = new StringBuilder();
        boolean firstRow = true;
        for (String raw : md.lines().toList()) {
            String line = raw.trim();
            if (!line.startsWith("|") || !line.endsWith("|")) continue;  // 跳过非表行 (头尾说明)
            if (SEPARATOR.matcher(line).matches()) continue;             // 跳过 |---| 分隔行
            String body = line.substring(1, line.length() - 1);          // 去首尾 |
            String[] cells = body.split("\\|", -1);
            for (int i = 0; i < cells.length; i++) {
                cells[i] = cells[i].trim().replace("\\|", "|");          // 反转义 \|
            }
            if (!firstRow) out.append("\n");
            out.append(toCsvLine(cells));
            firstRow = false;
        }
        return out.toString();
    }

    private static String toCsvLine(String[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(escapeCsvCell(cells[i]));
        }
        return sb.toString();
    }

    /** RFC 4180: 含逗号/引号/换行的字段用双引号包裹, 内部 " 转义为 "". */
    private static String escapeCsvCell(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
