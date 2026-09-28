package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.service.LongTaskNodeExecutionLogService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class LongTaskNodeExecutionLogController {
    private final LongTaskNodeExecutionLogService service;
    public LongTaskNodeExecutionLogController(LongTaskNodeExecutionLogService service){this.service=service;}
    @GetMapping("/api/long-task-node-execution-logs")
    public LongTaskNodeExecutionLogService.Page list(@RequestHeader(name="X-User-Id",required=false,defaultValue="") String userId,
            @RequestParam(defaultValue="mine") String scope, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize, @RequestParam(required=false) String from,
            @RequestParam(required=false) String to, @RequestParam(required=false) String status,
            @RequestParam(required=false) String flowName, @RequestParam(required=false) String nodeName,
            @RequestParam(required=false) String filterUserId) {
        int safePage=Math.max(1,page), safeSize=Math.min(100,Math.max(1,pageSize));
        return service.page(userId, "all".equalsIgnoreCase(scope), safePage-1, safeSize, from,to,status,flowName,nodeName,filterUserId);
    }
    @GetMapping("/api/long-task-node-execution-logs/{logId}")
    public Map<String,Object> detail(@RequestHeader(name="X-User-Id",required=false,defaultValue="") String userId,
                                     @RequestParam(defaultValue="mine") String scope, @PathVariable String logId) {
        Map<String,Object> value=service.detail(userId,"all".equalsIgnoreCase(scope),logId);
        if(value==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return value;
    }
}
