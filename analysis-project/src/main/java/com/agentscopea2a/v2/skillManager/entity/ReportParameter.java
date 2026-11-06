package com.agentscopea2a.v2.skillManager.entity;

import lombok.Data;

/** 参数池中每条已保存记录对应一次独立的报告执行。 */
@Data
public class ReportParameter {
    private Long id;
    private Long flowId;
    private String teamId;
    private String paramsJson;
    private String keyword;
    private Boolean autoEnabled;
    private Integer sortOrder;
    private String toReceiversJson;
    private String ccReceiversJson;
    private String notifyContactName;
    private String notifyContactPhone;
    private String executorsJson;
    private Boolean notifyEnabled;
    private String notifyBodyTemplate;
}
