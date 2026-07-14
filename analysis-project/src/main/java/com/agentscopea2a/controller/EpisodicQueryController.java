package com.agentscopea2a.controller;

import com.agentscopea2a.agent.memory.EpisodicQueryCriteria;
import com.agentscopea2a.agent.memory.EpisodicRecordVo;
import com.agentscopea2a.agent.memory.MySqlEpisodicMemory;
import com.agentscopea2a.service.SupervisorService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 调试接口：查询 episodic memory 消息时间线。
 * 用于排查 Agent 推理与端到端请求链路问题。
 */
@RestController
@RequestMapping("/debug")
public class EpisodicQueryController {

    private static final Logger log = LoggerFactory.getLogger(EpisodicQueryController.class);

    @Autowired
    private SupervisorService supervisorService;

    @GetMapping("/episodic")
    public ResponseEntity<?> queryTimeline(
            @RequestParam("conversationId") String conversationId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false, defaultValue = "200") Integer limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") Integer offset) {

        log.info("queryTimeline called: conversationId={}, limit={}, offset={}", conversationId, limit, offset);

        try {
            // 校验 conversationId 非空
            if (StringUtils.isBlank(conversationId)) {
                return ResponseEntity.badRequest().body("conversationId is required");
            }

            // 校验 limit / offset 非负
            if (limit != null && limit < 0) {
                return ResponseEntity.badRequest().body("limit must be >= 0");
            }
            if (offset != null && offset < 0) {
                return ResponseEntity.badRequest().body("offset must be >= 0");
            }

            // 获取 episodic memory 实例
            MySqlEpisodicMemory episodicMemory = supervisorService.getEpisodicMemory();
            if (episodicMemory == null) {
                return ResponseEntity.status(503).body("episodic memory not ready");
            }

            // 组装查询条件并执行
            EpisodicQueryCriteria criteria = EpisodicQueryCriteria.builder()
                    .conversationId(conversationId)
                    .from(from)
                    .to(to)
                    .role(role)
                    .q(q)
                    .limit(limit)
                    .offset(offset)
                    .build();

            List<EpisodicRecordVo> records = episodicMemory.queryTimeline(criteria);
            return ResponseEntity.ok(records);
        } catch (Throwable t) {
            log.error("queryTimeline failed", t);
            return ResponseEntity.internalServerError().body("query failed: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }
}
