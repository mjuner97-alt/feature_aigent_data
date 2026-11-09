package com.agentscopea2a.constant;

/**
 * 第三方平台及通知客户端使用的默认请求配置。
 */
public final class AgentToolConstant {
    private AgentToolConstant() {
    }

    public static final String DEFAULT_XTOKEN = "placeholder-xtoken";
    public static final String MANAGER_BASE_URL = "http://manager.sdc.cs.icbc";
    public static final String THIRD_PARTY_LOGIN_URL = MANAGER_BASE_URL + "/#login?origin=digitalQA";
    public static final String DEFAULT_MAIL_URL = MANAGER_BASE_URL + "/api/mail/send";
}
