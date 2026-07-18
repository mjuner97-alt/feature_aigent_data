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
package com.agentscopea2a.v2.dimension;

import com.agentscopea2a.v2.dimension.DimensionState.PeerDimension;
import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;
import com.agentscopea2a.v2.dimension.DimensionState.TimeDimension;
import com.agentscopea2a.v2.dimension.DimensionState.TimeDimensionType;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 对用户问题的分析结果，包含提问层级、指代关系和显式维度。
 *
 * <p>支持两种 JSON 格式：
 *
 * <ul>
 *   <li>嵌套格式：{@code "timeDimension": {"type": "VERSION", "values": [...]}}
 *   <li>扁平格式：{@code "timeDimensionType": "VERSION", "timeDimensionValues": [...]}
 * </ul>
 *
 * LLM 返回的通常是扁平格式，通过 @JsonAnySetter 自动兼容。
 */
public class QuestionAnalysis {

    /** 提问层级 */
    private QuestionLevel level;

    /** 是否包含指代词（"这个组"、"那个应用"等） */
    private boolean hasReference;

    /** 指代的维度类型 */
    private ReferenceType referenceType;

    /** 是否是原因分析类问题 */
    private boolean isCauseAnalysis;

    /** 用户在新问题中显式指定的维度（覆盖继承值） */
    private ExplicitDimensions explicitDimensions;

    public QuestionAnalysis() {}

    @JsonCreator
    public QuestionAnalysis(
            @JsonProperty("level") QuestionLevel level,
            @JsonProperty("hasReference") boolean hasReference,
            @JsonProperty("referenceType") ReferenceType referenceType,
            @JsonProperty("isCauseAnalysis") boolean isCauseAnalysis,
            @JsonProperty("explicitDimensions") ExplicitDimensions explicitDimensions) {
        this.level = level;
        this.hasReference = hasReference;
        this.referenceType = referenceType;
        this.isCauseAnalysis = isCauseAnalysis;
        this.explicitDimensions = explicitDimensions;
    }

    public QuestionLevel getLevel() {
        return level;
    }

    public void setLevel(QuestionLevel level) {
        this.level = level;
    }

    public boolean isHasReference() {
        return hasReference;
    }

    public void setHasReference(boolean hasReference) {
        this.hasReference = hasReference;
    }

    public ReferenceType getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(ReferenceType referenceType) {
        this.referenceType = referenceType;
    }

    public boolean isCauseAnalysis() {
        return isCauseAnalysis;
    }

    public void setCauseAnalysis(boolean causeAnalysis) {
        isCauseAnalysis = causeAnalysis;
    }

    public ExplicitDimensions getExplicitDimensions() {
        return explicitDimensions;
    }

    public void setExplicitDimensions(ExplicitDimensions explicitDimensions) {
        this.explicitDimensions = explicitDimensions;
    }

    // ==================== 枚举 ====================

    /** 提问层级 */
    public enum QuestionLevel {
        /** 涉及时间维度（季度或版本计划） */
        TIME,
        /** 涉及部门维度 */
        DEPARTMENT,
        /** 涉及组/应用/产品线维度 */
        PEER,
        /** 涉及人维度 */
        PERSON,
        /** 原因分析 */
        CAUSE,
        /** 时间范围查询（如"过去几个月"、"过去几个季度"） */
        TIME_RANGE;

        /**
         * LLM 有时返回 APPLICATION/TEAM/PRODUCT_LINE/REQUIREMENT 作为 level，统一映射为 PEER。
         */
        @JsonCreator
        public static QuestionLevel fromString(String value) {
            if (value == null) {
                return null;
            }
            return switch (value.toUpperCase()) {
                case "TEAM", "APPLICATION", "PRODUCT_LINE", "REQUIREMENT" -> PEER;
                default -> valueOf(value.toUpperCase());
            };
        }
    }

    /** 指代类型，覆盖所有维度层级 */
    public enum ReferenceType {
        /** 时间维度（"这个季度"/"这个版本"/"那个月"等） */
        TIME,
        /** 部门 */
        DEPARTMENT,
        /** 组 */
        TEAM,
        /** 应用 */
        APPLICATION,
        /** 产品线 */
        PRODUCT_LINE,
        /** 需求项 */
        REQUIREMENT
    }

    /**
     * 用户问题中显式指定的维度，非 null 字段将覆盖继承值。
     *
     * <p>支持两种 JSON 反序列化格式：
     *
     * <ul>
     *   <li>嵌套格式：{@code "timeDimension": {"type":"VERSION","values":[...]}}
     *   <li>扁平格式：{@code "timeDimensionType":"VERSION","timeDimensionValues":[...]}
     * </ul>
     */
    public static class ExplicitDimensions {

        private TimeDimension timeDimension;
        private List<String> departments;
        private PeerDimension peerDimension;
        private List<String> persons;

        /** 收集扁平格式的未知字段，用于手动组装嵌套对象 */
        private final Map<String, Object> extra = new HashMap<>();

        public ExplicitDimensions() {}

        @JsonCreator
        public ExplicitDimensions(
                @JsonProperty("timeDimension") TimeDimension timeDimension,
                @JsonProperty("departments") List<String> departments,
                @JsonProperty("peerDimension") PeerDimension peerDimension,
                @JsonProperty("persons") List<String> persons) {
            this.timeDimension = timeDimension;
            this.departments = departments;
            this.peerDimension = peerDimension;
            this.persons = persons;
        }

        /** 收集扁平格式字段（timeDimensionType, timeDimensionValues 等） */
        @JsonAnySetter
        public void setAny(String key, Object value) {
            extra.put(key, value);
        }

        /** 构建完成时，从扁平字段组装嵌套对象（如果嵌套对象尚未设置） */
        public void build() {
            if (timeDimension == null) {
                timeDimension = buildTimeDimension();
            }
            if (peerDimension == null) {
                peerDimension = buildPeerDimension();
            }
        }

        @SuppressWarnings("unchecked")
        private TimeDimension buildTimeDimension() {
            Object typeObj = extra.get("timeDimensionType");
            Object valuesObj = extra.get("timeDimensionValues");
            if (typeObj == null && valuesObj == null) {
                return null;
            }
            TimeDimensionType type = null;
            List<String> values = null;
            if (typeObj instanceof String typeStr) {
                type = TimeDimensionType.valueOf(typeStr);
            }
            if (valuesObj instanceof List<?> list) {
                values = (List<String>) list;
            }
            if (type != null && values != null && !values.isEmpty()) {
                return new TimeDimension(type, values);
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private PeerDimension buildPeerDimension() {
            Object typeObj = extra.get("peerDimensionType");
            Object valuesObj = extra.get("peerDimensionValues");
            if (typeObj == null && valuesObj == null) {
                return null;
            }
            PeerDimensionType type = null;
            List<String> values = null;
            if (typeObj instanceof String typeStr) {
                type = PeerDimensionType.valueOf(typeStr);
            }
            if (valuesObj instanceof List<?> list) {
                values = (List<String>) list;
            }
            if (type != null && values != null && !values.isEmpty()) {
                return new PeerDimension(type, values);
            }
            return null;
        }

        public TimeDimension getTimeDimension() {
            return timeDimension;
        }

        public void setTimeDimension(TimeDimension timeDimension) {
            this.timeDimension = timeDimension;
        }

        public List<String> getDepartments() {
            return departments;
        }

        public void setDepartments(List<String> departments) {
            this.departments = departments;
        }

        public PeerDimension getPeerDimension() {
            return peerDimension;
        }

        public void setPeerDimension(PeerDimension peerDimension) {
            this.peerDimension = peerDimension;
        }

        public List<String> getPersons() {
            return persons;
        }

        public void setPersons(List<String> persons) {
            this.persons = persons;
        }
    }
}
