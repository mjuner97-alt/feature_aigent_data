/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.harness.dimension;

/**
 * 维度继承方案中使用的 LLM Prompt 模板。
 */
public final class DimensionPrompts {

    private DimensionPrompts() {}

    /** 维度提取 Prompt */
    public static final String EXTRACT_DIMENSIONS_PROMPT =
            """
            第一步：在下面的回答中，找到直接给出最终答案的那一句话（通常是"最差的是X"、"最好的是X"、"X质量最高"这样的结论句）。
            如果没有明确的结论句，回答的是一组数据，则所有维度返回 null。

            第二步：只从这句结论话中提取维度，忽略回答中其他所有内容（表格、列表、排名、对比数据等一律忽略）。

            严格按 JSON 格式返回：
            {
              "timeDimensionType": "QUARTER|VERSION" 或 null,
              "timeDimensionValues": ["值"] 或 null,
              "departments": ["杭州开发X部"] 或 null,
              "peerDimensionType": "TEAM|APPLICATION|PRODUCT_LINE|REQUIREMENT" 或 null,
              "peerDimensionValues": ["值"] 或 null,
              "persons": ["姓名"] 或 null
            }

            规则：
            - departments：结论提到几个部门就提取几个（通常只有1个）
            - persons：结论提到几个人名就提取几个（通常只有1个）
            - peerDimensionValues：结论提到几个组/应用/产品线/需求项就提取几个（通常只有1个）
            - peerDimensionType 同级维度四选一：
              - TEAM(组)：xxx组
              - APPLICATION(应用)：F-xxx 全英文
              - PRODUCT_LINE(产品线)：xxx产品线
              - REQUIREMENT(需求项)：xxx需求项
            - timeDimensionValues：如果结论涉及时间范围（如"过去几个月"），提取所有涉及时间；否则提取结论中提到的那1个
            - 季度="xxx年x季度"(QUARTER)，版本="xxx年x月份版本"(VERSION)
            - 应用以"F-"开头，部门为"杭州开发X部"
            - 如果结论没有指向某个维度的值，该维度返回 null

            回答：{answer}
            """;
}
