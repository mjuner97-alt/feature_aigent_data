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
        var names = new HashMap<String, String>();
        if (!ids.isEmpty()) {
            people.selectByUserIds(ids).forEach(person -> names.putIfAbsent(person.getUserId(), displayName(person)));
        }
        return new Recipients(saved.to(), saved.cc(), names);
    }

    @GetMapping("/people")
    public List<Person> search(@RequestParam String department, @RequestParam String keyword,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        var name = department.strip();
        requireOwnDepartment(name, userId);
        if (keyword.isBlank()) return List.of();
        return people.searchDepartmentPeople(name, keyword.strip()).stream()
                .map(person -> new Person(person.getUserId(), displayName(person), name)).toList();
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
            var members = people.selectByUserIds(ids).stream()
                    .filter(person -> person.getDepartment() != null && name.equals(person.getDepartment().strip()))
                    .map(DeveloperPlPersonInfo::getUserId).collect(Collectors.toSet());
            if (!members.containsAll(ids)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认名单只能包含所选部门的人员");
            }
        }
        service.saveReportRecipients(name, to, cc);
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
