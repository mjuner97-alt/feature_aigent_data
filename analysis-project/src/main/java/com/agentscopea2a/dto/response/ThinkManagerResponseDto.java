package com.agentscopea2a.dto.response;

import lombok.Data;

@Data
public class ThinkManagerResponseDto extends ManagerResponseDto {

    private String type = "think";

    private ContentDto data;

    private boolean finish;
}
