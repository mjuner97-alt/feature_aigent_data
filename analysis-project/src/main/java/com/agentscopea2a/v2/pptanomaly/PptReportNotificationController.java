package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.v2.auth.controller.AuthController;
import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyController.FeedbackNotification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 部门报告通知配置接口，供登录用户查看默认名单，并按目标部门授权维护。 */
@RestController
@RequestMapping("/api/ppt-anomaly/report-notification")
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptReportNotificationController {
    private final PptAnomalyService service;
    private final DeveloperPlPersonInfoMapper people;

    /** 注入配置维护服务和人员台账查询接口。 */
    public PptReportNotificationController(PptAnomalyService service, DeveloperPlPersonInfoMapper people) {
        this.service = service;
        this.people = people;
    }

    /** 部门名称及当前用户对该部门的维护标记。 */
    public record Department(String name, boolean editable) {}

    /** 人员选择器所需的账号、姓名和所属部门。 */
    public record Person(String userId, String name, String department) {}

    /** 默认收件人、抄送人及账号对应的姓名。 */
    public record Recipients(List<String> to, List<String> cc, Map<String, String> names) {}

    /** 部门的默认通知名单和维护标记。 */
    public record Configuration(String department, boolean editable, Recipients recipients) {}

    /** 列出所有部门的报告通知配置，并标记每行是否可以维护。 */
    @GetMapping("/configurations")
    public List<Configuration> configurations(
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        PptAnomalyController.requireUser(userId);
        var saved = service.allReportRecipients();
        // 已保存的历史部门配置也纳入列表，人员台账调整后仍可查看。
        var visible = departments(userId, saved.keySet());
        var names = namesFor(saved.values().stream().flatMap(value -> Stream.concat(value.to().stream(), value.cc().stream()))
                .distinct().toList());
        return visible.stream().map(department -> {
            var value = saved.getOrDefault(department.name(), new FeedbackNotification(List.of(), List.of()));
            var departmentNames = new HashMap<String, String>();
            Stream.concat(value.to().stream(), value.cc().stream()).forEach(id -> {
                if (names.containsKey(id)) departmentNames.put(id, names.get(id));
            });
            return new Configuration(department.name(), department.editable(),
                    new Recipients(value.to(), value.cc(), departmentNames));
        }).toList();
    }

    /** 获取部门选项及当前用户的维护标记，供通知和督办人配置共用。 */
    @GetMapping("/departments")
    public List<Department> departments(
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        return departments(userId, List.of());
    }

    /** 合并人员台账、授权部门与已有配置中的部门。 */
    private List<Department> departments(String userId, Collection<String> configured) {
        var access = service.configDepartmentAccess(userId);
        var visible = new LinkedHashSet<>(service.configDirectoryDepartments());
        visible.addAll(access.departments());
        visible.addAll(configured);
        return visible.stream().filter(Objects::nonNull).map(String::strip).filter(name -> !name.isEmpty())
                .distinct().sorted().map(name -> new Department(name, access.canMaintain(name))).toList();
    }

    /** 读取指定部门的默认通知名单，供配置查看及发信时引用。 */
    @GetMapping
    public Recipients get(@RequestParam String department,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        department = department.strip();
        PptAnomalyController.requireUser(userId);
        var saved = service.reportRecipients(department);
        var ids = Stream.concat(saved.to().stream(), saved.cc().stream()).distinct().toList();
        return new Recipients(saved.to(), saved.cc(), namesFor(ids));
    }

    /** 校验目标部门的维护权限后，从全量人员台账搜索收件人候选。 */
    @GetMapping("/people")
    public List<Person> search(@RequestParam String department, @RequestParam String keyword,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        var name = department.strip();
        service.configDepartmentAccess(userId).requireMaintenance(name);
        if (keyword.isBlank()) return List.of();
        return people.searchPptPeople(keyword.strip(), "").stream()
                .map(person -> new Person(person.getUserId(), displayName(person), person.getDepartment())).toList();
    }

    /** 校验部门维护权限和收件账号后，保存默认收件人和抄送人。 */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@RequestParam String department, @RequestBody FeedbackNotification body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        var name = department.strip();
        service.configDepartmentAccess(userId).requireMaintenance(name);
        var to = PptAnomalyController.recipientIds(body.to());
        // 同一账号同时出现时，以收件人身份保留。
        var cc = PptAnomalyController.recipientIds(body.cc()).stream().filter(id -> !to.contains(id)).toList();
        var ids = Stream.concat(to.stream(), cc.stream()).toList();
        if (!ids.isEmpty()) {
            var known = people.selectByUserIds(ids).stream()
                    .map(DeveloperPlPersonInfo::getUserId).collect(Collectors.toSet());
            if (!known.containsAll(ids)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认名单中有人员台账无法识别的账号");
            }
        }
        service.saveReportRecipients(name, to, cc);
    }

    /** 批量补全通知名单中的人员姓名。 */
    private Map<String, String> namesFor(List<String> ids) {
        var names = new HashMap<String, String>();
        if (!ids.isEmpty()) {
            people.selectByUserIds(ids).forEach(person -> names.putIfAbsent(person.getUserId(), displayName(person)));
        }
        return names;
    }

    /** 姓名缺失时用统一认证号显示该人员。 */
    private static String displayName(DeveloperPlPersonInfo person) {
        return person.getName() == null || person.getName().isBlank() ? person.getUserId() : person.getName();
    }
}
