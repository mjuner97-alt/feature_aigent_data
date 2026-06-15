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
package com.agentscopea2a.harness.tools;

import com.agentscopea2a.harness.hooks.DataGroundingHook;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for known quality-data entities and per-department defect density.
 *
 * <p>{@link QualityTools} reads these constants for tool responses; {@link
 * DataGroundingHook} reads them to validate that the
 * Agent's final answer only mentions known entities/values. Previously each class kept its own
 * copy — see docs/enhancement-proposal.md P2-3.
 */
public final class KnownEntities {

    public static final List<String> DEPARTMENTS =
            List.of("杭州开发一部", "杭州开发二部", "杭州开发三部", "杭州开发四部", "杭州开发五部");

    public static final List<String> APPLICATIONS =
            List.of("F-CMS", "F-Loan", "F-Risk", "F-Pay", "F-Channel");

    public static final List<String> TEAMS = List.of("个贷组", "信用卡组", "风控组", "支付组", "渠道组");

    public static final List<String> PRODUCT_LINES =
            List.of("信贷产品线", "零售产品线", "风控产品线", "支付产品线", "渠道产品线");

    public static final List<String> PERSONS = List.of("张三", "李四", "王五", "赵六", "钱七");

    /** Per-department defect density (higher = worse quality). Deterministic mock data. */
    public static final Map<String, Double> DEPARTMENT_VERSION_QUALITY = new LinkedHashMap<>();

    static {
        DEPARTMENT_VERSION_QUALITY.put("杭州开发一部", 23.1);
        DEPARTMENT_VERSION_QUALITY.put("杭州开发二部", 13.1);
        DEPARTMENT_VERSION_QUALITY.put("杭州开发三部", 3.1);
        DEPARTMENT_VERSION_QUALITY.put("杭州开发四部", 6.1);
        DEPARTMENT_VERSION_QUALITY.put("杭州开发五部", 26.1);
    }

    /** All entity groups, useful for "did the response mention any unknown entity?" checks. */
    public static final List<List<String>> ALL_GROUPS =
            List.of(DEPARTMENTS, APPLICATIONS, TEAMS, PRODUCT_LINES, PERSONS);

    /** Flat list of all known entities — handy for bounding checks. */
    public static List<String> all() {
        return ALL_GROUPS.stream().flatMap(List::stream).toList();
    }

    /** Legacy array view for code paths that need {@code String[]}. */
    public static String[] departmentsArray() {
        return DEPARTMENTS.toArray(new String[0]);
    }

    public static String[] applicationsArray() {
        return APPLICATIONS.toArray(new String[0]);
    }

    public static String[] teamsArray() {
        return TEAMS.toArray(new String[0]);
    }

    public static String[] productLinesArray() {
        return PRODUCT_LINES.toArray(new String[0]);
    }

    public static String[] personsArray() {
        return PERSONS.toArray(new String[0]);
    }

    /** Convenience for hook iteration. */
    public static String[][] arrayGroups() {
        return new String[][] {
            departmentsArray(),
            applicationsArray(),
            teamsArray(),
            productLinesArray(),
            personsArray()
        };
    }

    private KnownEntities() {}

    /** Returns true if the text mentions any known entity from any group. */
    public static boolean mentionsKnownEntity(String text) {
        if (text == null || text.isEmpty()) return false;
        return Arrays.stream(arrayGroups()).flatMap(Arrays::stream).anyMatch(text::contains);
    }
}
