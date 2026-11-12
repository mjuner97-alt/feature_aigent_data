package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.common.PageResponse;
import com.agentscopea2a.v2.skillManager.service.TeamConfigService;
import java.util.Map;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 全局团队报告参数管理，登录用户均可维护。 */
@RestController
@RequestMapping("/api/long-task-team-config")
public class TeamConfigController {
    private final TeamConfigService service;
    public TeamConfigController(TeamConfigService service) {
        this.service = service;
    }
    /** 分页返回已保存的团队通知人员。 */
    @GetMapping
    public PageResponse<Map<String, Object>> list(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String department, @RequestParam(defaultValue = "") String team,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return service.list(keyword, department, team, page, pageSize);
    }
    /** 返回已保存配置对应的组织筛选选项。 */
    @GetMapping("/options")
    public List<Map<String, Object>> options() {
        return service.options();
    }
    /** 分页返回可导入的台账人员。 */
    @GetMapping("/people")
    public PageResponse<Map<String, Object>> people(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String department, @RequestParam(defaultValue = "") String team,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return service.people(keyword, department, team, page, pageSize);
    }
    /** 返回台账人员对应的组织筛选选项。 */
    @GetMapping("/people/options")
    public List<Map<String, Object>> peopleOptions() {
        return service.peopleOptions();
    }
    /** 按主键读取团队配置。 */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable("id") long id) { return service.get(id); }
    /** 新增一条默认通知人员配置。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody TeamConfigService.Input input, @RequestHeader("X-User-Id") String user) {
        checkWrite(user);
        return service.save(null, input);
    }
    /** 按认证号从台账批量新增通知人员。 */
    @PostMapping("/from-ledger")
    public TeamConfigService.BatchResult addFromLedger(@RequestBody TeamConfigService.BatchInput input,
                                                       @RequestHeader("X-User-Id") String user) {
        checkWrite(user);
        return service.addFromLedger(input);
    }
    /** 更新指定的默认通知人员配置。 */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") long id, @RequestBody TeamConfigService.Input input,
                                     @RequestHeader("X-User-Id") String user) {
        checkWrite(user);
        return service.save(id, input);
    }
    /** 删除指定配置，成功返回 204。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") long id, @RequestHeader("X-User-Id") String user) {
        checkWrite(user);
        service.delete(id);
    }
    /** 单独调整指定配置的主送或抄送类型。 */
    @PatchMapping("/{id}/recipient-type")
    public Map<String, Object> updateRecipientType(@PathVariable("id") long id,
            @RequestBody TeamConfigService.RecipientTypeInput input, @RequestHeader("X-User-Id") String user) {
        checkWrite(user);
        return service.updateRecipientType(id, input);
    }
    /** 要求写请求携带非空用户标识。 */
    private void checkWrite(String user) {
        if (user.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
}
