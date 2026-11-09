package com.agentscopea2a.v2.skillManager.entity;

import lombok.Data;

/** 参数池中每条已保存记录对应一次独立的报告执行。 */
@Data
public class ReportParameter {

    private Long id;
    private Long flowId;
    /** 部门报告使用空字符串，团队报告保存稳定团队标识。 */
    private String teamId;
    /** 对象固定公共参数与报告名称，不保存每轮变化的执行日期。 */
    private String paramsJson;
    private String keyword;
    /** 仅控制对象是否参与自动触发，不限制临时手动选择。 */
    private Boolean autoEnabled;
    private Integer sortOrder;
    /** 有序收件人名单；保存后不再随来源默认名单变化。 */
    private String toReceiversJson;
    private String ccReceiversJson;
    private String notifyContactName;
    private String notifyContactPhone;
    private String executorsJson;
    private Boolean notifyEnabled;
    private String notifyBodyTemplate;
}
