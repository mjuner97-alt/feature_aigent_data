package com.agentscopea2a.dto.response;

import lombok.Data;

@Data
public class TextManagerResponseDto extends ManagerResponseDto{

    private String type = "text";

    private ContentDto data;

    private boolean finish;
}
