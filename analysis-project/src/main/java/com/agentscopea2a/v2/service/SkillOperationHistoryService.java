package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.SkillOperationHistory;
import com.agentscopea2a.mapper.mysql.SkillOperationHistoryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作历史 Service。记录 CREATE/UPDATE/DELETE/PUBLISH/APPROVE/REJECT/DISABLE/ENABLE/REFERENCE。
 * LIKE/UNLIKE 不记录(高频,审计价值低)。
 */
@Service
public class SkillOperationHistoryService {

    private final SkillOperationHistoryMapper mapper;

    public SkillOperationHistoryService(SkillOperationHistoryMapper mapper) {
        this.mapper = mapper;
    }

    public void record(Long skillId, Long publishId, String operator,
                       String operation, String beforeData, String afterData) {
        mapper.insert(SkillOperationHistory.builder()
                .skillId(skillId)
                .publishId(publishId)
                .operator(operator)
                .operation(operation)
                .beforeData(beforeData)
                .afterData(afterData)
                .createdAt(LocalDateTime.now())
                .build());
    }

    public List<SkillOperationHistory> selectBySkillId(Long skillId) {
        return mapper.selectBySkillId(skillId);
    }

    public List<SkillOperationHistory> selectByOperator(String operator) {
        return mapper.selectByOperator(operator);
    }
}
