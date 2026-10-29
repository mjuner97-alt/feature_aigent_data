package com.agentscopea2a.v2.skillManager.entity;
import lombok.*;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LongTaskTag { private Long id; private String name; private LocalDateTime createdAt; private LocalDateTime updatedAt; }
