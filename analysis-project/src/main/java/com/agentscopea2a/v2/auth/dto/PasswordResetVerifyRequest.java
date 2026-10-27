package com.agentscopea2a.v2.auth.dto;
import lombok.Data;
@Data public class PasswordResetVerifyRequest { private String userId; private String code; }
