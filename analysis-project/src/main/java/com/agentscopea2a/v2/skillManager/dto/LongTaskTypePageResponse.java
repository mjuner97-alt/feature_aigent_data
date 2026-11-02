package com.agentscopea2a.v2.skillManager.dto;

import java.util.List;

/** 流程类型分页结果：当前页数据、总数及分页参数。 */
public record LongTaskTypePageResponse(
        /** 当前页类型列表。 */ List<LongTaskTypeResponse> items,
        /** 符合筛选条件的总记录数。 */ long total,
        /** 当前页码，从 1 开始。 */ int page,
        /** 每页记录数。 */ int pageSize) {}



