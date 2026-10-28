package com.agentscopea2a.v2.skillManager.dto;
import java.time.LocalDateTime;
public record LongTaskTypeResponse(Long id,String name,String description,Integer sortOrder,Boolean enabled,LocalDateTime createdAt,LocalDateTime updatedAt) {}
