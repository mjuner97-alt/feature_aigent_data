package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeAttempt;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeExecution;
import com.agentscopea2a.mapper.ck.LongTaskNodeExecutionLogMapper;
import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Service
public class LongTaskNodeExecutionLogService {
    static final int MAX_BYTES = 256 * 1024;
    private final LongTaskNodeExecutionLogMapper mapper;
    private final MockOrgService orgService;
    private final DeveloperPlPersonInfoMapper personInfoMapper;
    public LongTaskNodeExecutionLogService(LongTaskNodeExecutionLogMapper mapper, MockOrgService orgService,
                                            DeveloperPlPersonInfoMapper personInfoMapper) {
        this.mapper = mapper; this.orgService = orgService; this.personInfoMapper = personInfoMapper;
    }

    public void record(SkillFlowExecution flow, SkillFlowNodeExecution node, SkillFlowNodeAttempt attempt,
                       String params, String output, String error) {
        if (flow == null || node == null || attempt == null || node.getScriptId() == null || node.getScriptId().isBlank()) return;
        Value p = truncate(params), o = truncate(output), e = truncate(error);
        try {
            Map<String, Object> log = new HashMap<>();
            log.put("logId", "node-attempt-" + attempt.getId());
            log.put("flowExecutionId", flow.getId());
            log.put("nodeExecutionId", node.getId());
            log.put("attemptId", attempt.getId());
            log.put("attemptNo", attempt.getAttemptNo());
            log.put("userId", nvl(flow.getTriggerUserId()));
            log.put("flowName", nvl(flow.getFlowName()));
            log.put("nodeKey", nvl(node.getNodeKey()));
            log.put("nodeName", nvl(node.getNodeName()));
            log.put("scriptId", nvl(node.getScriptId()));
            log.put("executionIp", nvl(attempt.getExecutionIp()));
            log.put("status", attempt.getStatus() == null ? "UNKNOWN" : attempt.getStatus().name());
            log.put("retryable", Boolean.TRUE.equals(attempt.getRetryable()));
            log.put("errorCode", nvl(attempt.getErrorCode()));
            log.put("startedAt", ts(attempt.getStartedAt()));
            log.put("completedAt", ts(attempt.getCompletedAt()));
            log.put("durationMs", attempt.getDurationMs() == null ? 0L : attempt.getDurationMs());
            log.put("paramsJson", p.text);
            log.put("outputText", o.text);
            log.put("errorMessage", e.text);
            log.put("paramsTruncated", p.truncated);
            log.put("outputTruncated", o.truncated);
            log.put("errorTruncated", e.truncated);
            log.put("paramsBytes", p.bytes);
            log.put("outputBytes", o.bytes);
            log.put("errorBytes", e.bytes);
            mapper.insert(log);
        } catch (Exception ignored) {
            ignored.printStackTrace();
            /* observability must not affect workflow */ }
    }

    public Map<String,Object> detail(String userId, boolean all, String id) {
        Map<String,Object> row = mapper.detail(id, userId, all);
        if (row == null) return null;
        String owner = String.valueOf(row.get("user_id"));
        row.put("user_name", orgService.getUserNameMap(List.of(owner)).getOrDefault(owner, ""));
        return row;
    }
    public Page page(String userId, boolean all, int page, int size, String from, String to, String status,
                     String flowName, String nodeName, String userKeyword, Long flowExecutionId) {
        List<String> filterUserIds = resolveUserIds(userKeyword);
        long total = mapper.count(userId, all, from, to, status, flowName, nodeName, userKeyword,
                filterUserIds, flowExecutionId);
        List<Map<String,Object>> items = mapper.page(userId, all, from, to, status, flowName, nodeName,
                userKeyword, filterUserIds, flowExecutionId, page * size, size);
        Map<String, String> names = orgService.getUserNameMap(items.stream().map(row -> String.valueOf(row.get("user_id"))).toList());
        items.forEach(row -> row.put("user_name", names.getOrDefault(String.valueOf(row.get("user_id")), "")));
        return new Page(items, page + 1, size, total);
    }
    private List<String> resolveUserIds(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        List<DeveloperPlPersonInfo> rows = personInfoMapper.selectByKeyword(keyword.trim());
        return rows == null ? List.of() : rows.stream().map(DeveloperPlPersonInfo::getUserId)
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
    }
    private static String nvl(String s){return s==null?"":s;} private static java.sql.Timestamp ts(LocalDateTime t){return t==null?java.sql.Timestamp.valueOf(LocalDateTime.of(1970,1,1,0,0)):java.sql.Timestamp.valueOf(t);}
    private static Value truncate(String s){ if(s==null)s=""; byte[] b=s.getBytes(StandardCharsets.UTF_8); if(b.length<=MAX_BYTES)return new Value(s,false,b.length); return new Value(new String(b,0,MAX_BYTES,StandardCharsets.UTF_8),true,b.length); }
    private record Value(String text, boolean truncated, long bytes){} public record Page(List<Map<String,Object>> items,int page,int pageSize,long total){}
}
