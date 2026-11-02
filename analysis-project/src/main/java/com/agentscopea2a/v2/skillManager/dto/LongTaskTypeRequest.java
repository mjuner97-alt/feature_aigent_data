package com.agentscopea2a.v2.skillManager.dto;

/** 创建或修改流程类型时提交的字段。 */
public record LongTaskTypeRequest(
        /** 类型名称，不能为空且不能与已有类型重复。 */ String name,
        /** 类型说明，便于前端展示和维护。 */ String description,
        /** 展示排序值，数值越小越靠前且不能重复。 */ Integer sortOrder,
        /** 是否启用；为空时创建默认启用、修改时沿用原值。 */ Boolean enabled) {}



