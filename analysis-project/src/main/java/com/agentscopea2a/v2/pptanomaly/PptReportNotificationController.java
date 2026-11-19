package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.v2.auth.controller.AuthController;
import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyController.FeedbackNotification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/ppt-anomaly/report-notification")
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptReportNotificationController {
    private final PptAnomalyService service;
    private final DeveloperPlPersonInfoMapper people;

    public PptReportNotificationController(PptAnomalyService service, DeveloperPlPersonInfoMapper people) {
        this.service = service;
        this.people = people;
    }

    public record Department(String name, boolean editable) {}
    public record Person(String userId, String name, String department) {}
    public record Recipients(List<String> to, List<String> cc, Map<String, String> names) {}

    public record Configuration(String department, boolean editable, Recipients recipients) {}

    @GetMapping("/configurations")
    public List<Configuration> configurations(
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        var visible = departments(userId);
        var saved = service.reportRecipientsForDepartments(visible.stream().map(Department::name).toList());
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

    @GetMapping("/departments")
    public List<Department> departments(
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        var own = ownDepartments(userId);
        var visible = isProductDepartment(userId) ? people.selectAllDepartments() : own;
        return visible.stream().filter(Objects::nonNull).map(String::strip).filter(name -> !name.isEmpty())
                .distinct().sorted().map(name -> new Department(name, own.contains(name))).toList();
    }

    @GetMapping
    public Recipients get(@RequestParam String department,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        department = department.strip();
        if (!ownDepartments(userId).contains(department) && !isProductDepartment(userId)) throw forbidden();
        var saved = service.reportRecipients(department);
        var ids = Stream.concat(saved.to().stream(), saved.cc().stream()).distinct().toList();
        return new Recipients(saved.to(), saved.cc(), namesFor(ids));
    }

    @GetMapping("/people")
    public List<Person> search(@RequestParam String department, @RequestParam String keyword,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        var name = department.strip();
        requireOwnDepartment(name, userId);
        if (keyword.isBlank()) return List.of();
        return people.searchPptPeople(keyword.strip(), "").stream()
                .map(person -> new Person(person.getUserId(), displayName(person), person.getDepartment())).toList();
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@RequestParam String department, @RequestBody FeedbackNotification body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        var name = department.strip();
        requireOwnDepartment(name, userId);
        var to = PptAnomalyController.recipientIds(body.to());
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

    private Map<String, String> namesFor(List<String> ids) {
        var names = new HashMap<String, String>();
        if (!ids.isEmpty()) {
            people.selectByUserIds(ids).forEach(person -> names.putIfAbsent(person.getUserId(), displayName(person)));
        }
        return names;
    }

    private List<String> ownDepartments(String userId) {
        PptAnomalyController.requireUser(userId);
        return people.selectByUserId(userId).stream().map(DeveloperPlPersonInfo::getDepartment)
                .filter(Objects::nonNull).map(String::strip).filter(name -> !name.isEmpty()).distinct().toList();
    }

    private void requireOwnDepartment(String department, String userId) {
        if (!ownDepartments(userId).contains(department)) throw forbidden();
    }

    private boolean isProductDepartment(String userId) {
        return PptAnomalyController.isProductDepartmentUser(userId, people);
    }

    private static String displayName(DeveloperPlPersonInfo person) {
        return person.getName() == null || person.getName().isBlank() ? person.getUserId() : person.getName();
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "没有操作该部门通知配置的权限");
    }
}
