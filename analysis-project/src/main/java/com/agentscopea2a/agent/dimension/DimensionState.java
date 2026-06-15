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
package com.agentscopea2a.agent.dimension;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.state.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 结构化维度状态，存储多轮对话中各维度的上下文信息。
 *
 * <p>维度层级：时间维度(季度/版本计划 二选一) → 部门 → 业务同级维度(组/应用/产品线 三选一) → 人
 *
 * <p>所有维度均为 List 类型，支持多值或 null。
 */
public class DimensionState implements State {

    /** 时间维度（季度/版本计划 二选一，值可有多个） */
    private TimeDimension timeDimension;

    /** 部门（可有多个） */
    private List<String> departments;

    /** 业务同级维度（组/应用/产品线 三选一，值可有多个） */
    private PeerDimension peerDimension;

    /** 人（可有多个） */
    private List<String> persons;

    public DimensionState() {}

    @JsonCreator
    public DimensionState(
            @JsonProperty("timeDimension") TimeDimension timeDimension,
            @JsonProperty("departments") List<String> departments,
            @JsonProperty("peerDimension") PeerDimension peerDimension,
            @JsonProperty("persons") List<String> persons) {
        this.timeDimension = timeDimension;
        this.departments = departments;
        this.peerDimension = peerDimension;
        this.persons = persons;
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

    /**
     * 深拷贝当前维度状态
     */
    public DimensionState deepCopy() {
        DimensionState copy = new DimensionState();
        copy.setTimeDimension(this.timeDimension != null ? this.timeDimension.deepCopy() : null);
        copy.setDepartments(this.departments != null ? new ArrayList<>(this.departments) : null);
        copy.setPeerDimension(this.peerDimension != null ? this.peerDimension.deepCopy() : null);
        copy.setPersons(this.persons != null ? new ArrayList<>(this.persons) : null);
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DimensionState that = (DimensionState) o;
        return Objects.equals(timeDimension, that.timeDimension)
                && Objects.equals(departments, that.departments)
                && Objects.equals(peerDimension, that.peerDimension)
                && Objects.equals(persons, that.persons);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeDimension, departments, peerDimension, persons);
    }

    /**
     * Serializes the dimension state to a deterministic cache key string.
     *
     * <p>Format: {@code time=VERSION:2026年4月份版本|dept=杭州开发一部|peer=TEAM:个贷组|person=张三}
     *
     * <p>Returns empty string if no dimensions are set (not cacheable).
     */
    public String toCacheKey() {
        StringBuilder sb = new StringBuilder();
        if (timeDimension != null && !timeDimension.isEmpty()) {
            sb.append("time=")
                    .append(timeDimension.getType())
                    .append(":")
                    .append(String.join(",", timeDimension.getValues()));
        }
        if (departments != null && !departments.isEmpty()) {
            if (!sb.isEmpty()) sb.append("|");
            sb.append("dept=").append(String.join(",", departments));
        }
        if (peerDimension != null && !peerDimension.isEmpty()) {
            if (!sb.isEmpty()) sb.append("|");
            sb.append("peer=")
                    .append(peerDimension.getType())
                    .append(":")
                    .append(String.join(",", peerDimension.getValues()));
        }
        if (persons != null && !persons.isEmpty()) {
            if (!sb.isEmpty()) sb.append("|");
            sb.append("person=").append(String.join(",", persons));
        }
        return sb.toString();
    }

    /** Returns true if at least one dimension is set. */
    public boolean hasDimensions() {
        return (timeDimension != null && !timeDimension.isEmpty())
                || (departments != null && !departments.isEmpty())
                || (peerDimension != null && !peerDimension.isEmpty())
                || (persons != null && !persons.isEmpty());
    }

    @Override
    public String toString() {
        return "DimensionState{"
                + "timeDimension="
                + timeDimension
                + ", departments="
                + departments
                + ", peerDimension="
                + peerDimension
                + ", persons="
                + persons
                + '}';
    }

    // ==================== 内嵌类型 ====================

    /** 时间维度类型：季度与版本计划二选一 */
    public enum TimeDimensionType {
        QUARTER,
        VERSION
    }

    /** 业务同级维度类型：组/应用/产品线/需求项 四选一 */
    public enum PeerDimensionType {
        TEAM,
        APPLICATION,
        PRODUCT_LINE,
        REQUIREMENT
    }

    /**
     * 时间维度，type 标识季度还是版本，values 可有多个值。
     */
    public static class TimeDimension implements State {

        private TimeDimensionType type;
        private List<String> values;

        public TimeDimension() {}

        @JsonCreator
        public TimeDimension(
                @JsonProperty("type") TimeDimensionType type,
                @JsonProperty("values") List<String> values) {
            this.type = type;
            this.values = values;
        }

        public TimeDimensionType getType() {
            return type;
        }

        public void setType(TimeDimensionType type) {
            this.type = type;
        }

        public List<String> getValues() {
            return values;
        }

        public void setValues(List<String> values) {
            this.values = values;
        }

        public boolean isEmpty() {
            return values == null || values.isEmpty();
        }

        public TimeDimension deepCopy() {
            return new TimeDimension(
                    this.type, this.values != null ? new ArrayList<>(this.values) : null);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TimeDimension that = (TimeDimension) o;
            return type == that.type && Objects.equals(values, that.values);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, values);
        }

        @Override
        public String toString() {
            return "TimeDimension{type=" + type + ", values=" + values + '}';
        }
    }

    /**
     * 业务同级维度，type 标识种类，values 可有多个值。
     */
    public static class PeerDimension implements State {

        private PeerDimensionType type;
        private List<String> values;

        public PeerDimension() {}

        @JsonCreator
        public PeerDimension(
                @JsonProperty("type") PeerDimensionType type,
                @JsonProperty("values") List<String> values) {
            this.type = type;
            this.values = values;
        }

        public PeerDimensionType getType() {
            return type;
        }

        public void setType(PeerDimensionType type) {
            this.type = type;
        }

        public List<String> getValues() {
            return values;
        }

        public void setValues(List<String> values) {
            this.values = values;
        }

        public boolean isEmpty() {
            return values == null || values.isEmpty();
        }

        public PeerDimension deepCopy() {
            return new PeerDimension(
                    this.type, this.values != null ? new ArrayList<>(this.values) : null);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PeerDimension that = (PeerDimension) o;
            return type == that.type && Objects.equals(values, that.values);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, values);
        }

        @Override
        public String toString() {
            return "PeerDimension{type=" + type + ", values=" + values + '}';
        }
    }
}
