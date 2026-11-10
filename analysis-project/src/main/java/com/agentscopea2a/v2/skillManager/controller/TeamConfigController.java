package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.auth.service.AdminRoleService;
import com.agentscopea2a.v2.common.PageResponse;
import com.agentscopea2a.v2.skillManager.service.TeamConfigService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 全局团队报告参数管理，沿用配置广场的生产环境写权限。 */
@RestController
@RequestMapping("/api/long-task-team-config")
public class TeamConfigController {
    private final TeamConfigService service;
    private final AdminRoleService roles;
    public TeamConfigController(TeamConfigService service, AdminRoleService roles) {
        this.service = service;
        this.roles = roles;
    }
    @GetMapping
    public PageResponse<Map<String, Object>> list(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return service.list(keyword, page, pageSize);
    }
    @GetMapping("/people")
    public PageResponse<Map<String, Object>> people(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return service.people(keyword, page, pageSize);
    }
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable("id") long id) { return service.get(id); }
    @PostMapping
    public Map<String, Object> create(@RequestBody TeamConfigService.Input input, @RequestHeader("X-User-Id") String user) {
        checkWrite(user);
        return service.save(null, input);
    }
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") long id, @RequestBody TeamConfigService.Input input,
                                     @RequestHeader("X-User-Id") String user) {
        checkWrite(user);
        return service.save(id, input);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") long id, @RequestHeader("X-User-Id") String user) {
        checkWrite(user);
        service.delete(id);
    }
    private void checkWrite(String user) {
        if (user.isBlank() || !roles.canEditConfig(user))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前用户无配置修改权限");
    }
}
