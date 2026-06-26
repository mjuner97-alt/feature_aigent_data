package com.agentscopea2a.dto.response;

import lombok.Data;

@Data
public class ManagerResponseDto {

    private Integer code;
    private String errorMsg;
    private String ansUUID;
    private String conversationId;
    private String fromType;
}
