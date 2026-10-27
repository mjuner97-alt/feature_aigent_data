package com.agentscopea2a.v2.auth.dto;
import lombok.Data;
@Data public class PasswordResetCompleteRequest { private String userId; private String resetSession; private String newPassword; }
