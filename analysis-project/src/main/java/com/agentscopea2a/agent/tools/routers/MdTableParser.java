/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.agent.tools.routers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Markdown 表格解析器。
 *
 * <p>解析形如下面的 Markdown 表格,得到表头 + 每行数据:
 * <pre>
 * | 列1 | 列2 | 列3 |
 * |--|--|--|
 * | a  | b  | c  |
 * </pre>
 */
public class MdTableParser {

    /** 表头列名(顺序保留)。 */
    private final List<String> headers = new ArrayList<>();

    /** 每行的列值列表,顺序与 headers 对齐。 */
    private final List<List<String>> rows = new ArrayList<>();

    private MdTableParser() {}

    /**
     * 解析一段 Markdown 文本中第一个表格。
     *
     * @param markdown 含表格的 Markdown 内容
     * @return 解析后的实例;若未找到表格,headers/rows 均为空
     */
    public static MdTableParser parse(String markdown) {
        MdTableParser parser = new MdTableParser();
        if (markdown == null || markdown.isBlank()) {
            return parser;
        }
        String[] lines = markdown.split("\\R");
        boolean inTable = false;
        boolean headerParsed = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("|") && line.endsWith("|")) {
                String[] cols = splitRow(line);
                if (!inTable) {
                    inTable = true;
                    parser.headers.addAll(Arrays.asList(cols));
                    continue;
                }
                if (!headerParsed) {
                    // 第二行通常是 |--|--| 分隔符,跳过
                    headerParsed = true;
                    continue;
                }
                parser.rows.add(Arrays.asList(cols));
            } else if (inTable) {
                // 遇到非表格行,本表结束
                break;
            }
        }
        return parser;
    }

    private static String[] splitRow(String line) {
        // 去掉首尾 |,再按 | 分隔,最后去除每个 cell 的两端空白
        String trimmed = line.substring(1, line.length() - 1);
        String[] parts = trimmed.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    public List<String> getHeaders() {
        return headers;
    }

    public List<List<String>> getRows() {
        return rows;
    }
}
