package com.agentscopea2a.v2.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/** 会话记录下的资源行为记录查询接口。 */
@RestController
@RequestMapping("/api/session-records/behavior")
public class ResourceChangeAuditController {
    private final ResourceChangeAuditService service;

    /** 注入资源变更审计服务。 */
    public ResourceChangeAuditController(ResourceChangeAuditService service) {
        this.service = service;
    }

    /** 根据筛选条件返回分页行为记录。 */
    @GetMapping
    public Map<String, Object> page(@RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String operatorKeyword,
            @RequestParam(required = false) String resourceKeyword,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return service.page(resourceType, operationType, operatorKeyword, resourceKeyword,
                keyword, startTime, endTime, environment, page, pageSize);
    }

    @GetMapping("/{eventId}")
    public Map<String, Object> detail(@PathVariable String eventId) {
        return service.detail(eventId);
    }
}
