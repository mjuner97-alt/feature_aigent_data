package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeAttempt;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNodeExecution;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class LongTaskNodeExecutionLogService {
    static final int MAX_BYTES = 256 * 1024;
    private final JdbcTemplate jdbc;
    private final MockOrgService orgService;
    public LongTaskNodeExecutionLogService(@Qualifier("clickHouseDataSource") javax.sql.DataSource ds, MockOrgService orgService) { this.jdbc = new JdbcTemplate(ds); this.orgService = orgService; }

    public void record(SkillFlowExecution flow, SkillFlowNodeExecution node, SkillFlowNodeAttempt attempt,
                       String params, String output, String error) {
        if (flow == null || node == null || attempt == null || node.getScriptId() == null || node.getScriptId().isBlank()) return;
        Value p = truncate(params), o = truncate(output), e = truncate(error);
        try {
            jdbc.update("INSERT INTO default.long_task_node_execution_log "
                    + "(log_id,flow_execution_id,node_execution_id,attempt_id,attempt_no,user_id,flow_name,node_key,node_name,script_id,status,retryable,error_code,started_at,completed_at,duration_ms,params_json,output_text,error_message,params_truncated,output_truncated,error_truncated,params_bytes,output_bytes,error_bytes) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    "node-attempt-" + attempt.getId(), flow.getId(), node.getId(), attempt.getId(), attempt.getAttemptNo(),
                    nvl(flow.getTriggerUserId()), nvl(flow.getFlowName()), nvl(node.getNodeKey()), nvl(node.getNodeName()), nvl(node.getScriptId()),
                    attempt.getStatus() == null ? "UNKNOWN" : attempt.getStatus().name(), Boolean.TRUE.equals(attempt.getRetryable()), nvl(attempt.getErrorCode()),
                    ts(attempt.getStartedAt()), ts(attempt.getCompletedAt()), attempt.getDurationMs() == null ? 0L : attempt.getDurationMs(),
                    p.text, o.text, e.text, p.truncated, o.truncated, e.truncated, p.bytes, o.bytes, e.bytes);
        } catch (Exception ignored) { /* observability must not affect workflow */ }
    }

    public Map<String,Object> detail(String userId, boolean all, String id) {
        String sql = "SELECT * FROM default.long_task_node_execution_log WHERE log_id=?" + (all ? "" : " AND user_id=?") + " LIMIT 1";
        List<Map<String,Object>> rows = all ? jdbc.queryForList(sql, id) : jdbc.queryForList(sql, id, userId);
        if (rows.isEmpty()) return null;
        Map<String,Object> row = rows.get(0);
        String owner = String.valueOf(row.get("user_id"));
        row.put("user_name", orgService.getUserNameMap(List.of(owner)).getOrDefault(owner, ""));
        return row;
    }
    public Page page(String userId, boolean all, int page, int size, String from, String to, String status, String flowName, String nodeName, String filterUserId) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (!all) { where.append(" AND user_id=?"); args.add(userId); }
        if (from != null && !from.isBlank()) { where.append(" AND started_at>=parseDateTimeBestEffort(?)"); args.add(from); }
        if (to != null && !to.isBlank()) { where.append(" AND started_at<parseDateTimeBestEffort(?)"); args.add(to); }
        if (status != null && !status.isBlank()) { where.append(" AND status=?"); args.add(status); }
        if (flowName != null && !flowName.isBlank()) { where.append(" AND flow_name ILIKE ?"); args.add("%"+flowName+"%"); }
        if (nodeName != null && !nodeName.isBlank()) { where.append(" AND node_name ILIKE ?"); args.add("%"+nodeName+"%"); }
        if (filterUserId != null && !filterUserId.isBlank()) { where.append(" AND user_id=?"); args.add(filterUserId.trim()); }
        long total = jdbc.queryForObject("SELECT count() FROM default.long_task_node_execution_log"+where, Long.class, args.toArray());
        args.add(page * size); args.add(size);
        List<Map<String,Object>> items = jdbc.queryForList("SELECT log_id,flow_execution_id,node_execution_id,attempt_no,user_id,flow_name,node_name,script_id,status,retryable,error_code,started_at,completed_at,duration_ms,error_message FROM default.long_task_node_execution_log"+where+" ORDER BY started_at DESC LIMIT ?,?", args.toArray());
        Map<String, String> names = orgService.getUserNameMap(items.stream().map(row -> String.valueOf(row.get("user_id"))).toList());
        items.forEach(row -> row.put("user_name", names.getOrDefault(String.valueOf(row.get("user_id")), "")));
        return new Page(items, page + 1, size, total);
    }
    private static String nvl(String s){return s==null?"":s;} private static java.sql.Timestamp ts(LocalDateTime t){return t==null?java.sql.Timestamp.valueOf(LocalDateTime.of(1970,1,1,0,0)):java.sql.Timestamp.valueOf(t);}
    private static Value truncate(String s){ if(s==null)s=""; byte[] b=s.getBytes(StandardCharsets.UTF_8); if(b.length<=MAX_BYTES)return new Value(s,false,b.length); return new Value(new String(b,0,MAX_BYTES,StandardCharsets.UTF_8),true,b.length); }
    private record Value(String text, boolean truncated, long bytes){} public record Page(List<Map<String,Object>> items,int page,int pageSize,long total){}
}
