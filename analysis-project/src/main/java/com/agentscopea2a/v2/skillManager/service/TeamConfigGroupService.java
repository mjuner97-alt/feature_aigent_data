package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.mapper.gaussCommon.TeamLedgerMapper;
import com.agentscopea2a.v2.common.PageResponse;
import com.agentscopea2a.v2.skillManager.mapper.TeamConfigMapper;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

/** 维护团队默认通知配置。存量任务的独立参数池由原任务服务管理。 */
@Service
public class TeamConfigGroupService {
    private final TeamConfigMapper mapper;
    private final TeamLedgerMapper ledger;
    private final MockOrgService org;

    public TeamConfigGroupService(TeamConfigMapper mapper, TeamLedgerMapper ledger, MockOrgService org) {
        this.mapper = mapper;
        this.ledger = ledger;
        this.org = org;
    }

    public record Recipient(String userId, String name, String positionRole, Boolean enabled, String remark) {}
    public record GroupInput(Long id, Long version, String department, String team, List<Recipient> to, List<Recipient> cc) {}
    public record Group(long id, long version, String department, String team, List<Recipient> to, List<Recipient> cc) {}

    @Transactional(value = "gaussCustomerTransactionManager", readOnly = true,
        isolation = Isolation.REPEATABLE_READ)
    public PageResponse<Group> list(String keyword, String department, String team, int page, int pageSize) {
        int currentPage = Math.max(1, page), size = Math.max(1, Math.min(100, pageSize));
        String term = text(keyword), dept = text(department), group = text(team);
        int total = mapper.countGroups(term, dept, group);
        var rows = mapper.listGroups(term, dept, group, size, ((long) currentPage - 1) * size).stream()
            .map(this::group).toList();
        return new PageResponse<>(rows, currentPage, size, total);
    }

    private Group group(Map<String, Object> row) {
        String department = text(row.get("dev_dept")), team = text(row.get("stat_group"));
        var to = new ArrayList<Recipient>();
        var cc = new ArrayList<Recipient>();
        for (var person : mapper.groupPeople(department, team)) {
            var recipient = new Recipient(text(person.get("user_id")), text(person.get("user_name")),
                text(person.get("position_role")), !Boolean.FALSE.equals(person.get("enabled")), text(person.get("remark")));
            ("CC".equals(person.get("recipient_type")) ? cc : to).add(recipient);
        }
        return new Group(number(row, "id"), number(row, "version"), department, team, to, cc);
    }

    /** 整批保存到配置表，任何一条校验或写入失败均回滚。 */
    @Transactional("gaussCustomerTransactionManager")
    public void saveGroups(List<GroupInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 100) throw badRequest("请选择 1 至 100 个团队");
        var normalized = new ArrayList<GroupInput>();
        var keys = new HashSet<List<String>>();
        var ids = new HashSet<Long>();
        for (var input : inputs) {
            if (input == null) throw badRequest("团队配置不能为空");
            String dept = required(input.department(), "部门", 200);
            String team = limited(input.team(), "团队", 200);
            if (!keys.add(List.of(dept, team)) || (input.id() != null && !ids.add(input.id())))
                throw badRequest("同一批次不能重复保存团队");
            var to = recipients(input.to());
            var cc = recipients(input.cc());
            var people = new HashSet<String>();
            for (var person : concat(to, cc))
                if (!people.add(person.userId())) throw badRequest("同一团队的收件人和抄送人不能重复");
            if (people.size() > 500) throw badRequest("每个团队最多配置 500 位通知人员");
            normalized.add(new GroupInput(input.id(), input.version(), dept, team, to, cc));
        }
        // 所有入口按组织名称顺序取得团队锁。
        normalized.sort(Comparator.comparing(GroupInput::department).thenComparing(GroupInput::team));
        try {
            for (var input : normalized) save(input);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "团队已存在，请编辑已有配置");
        }
    }

    private void save(GroupInput input) {
        long id;
        if (input.id() == null) {
            var row = new HashMap<String, Object>();
            row.put("department", input.department());
            row.put("team", input.team());
            mapper.insertGroup(row);
            id = number(row, "id");
        } else {
            var row = locked(input.id(), input.version());
            if (!input.department().equals(text(row.get("dev_dept"))) || !input.team().equals(text(row.get("stat_group"))))
                throw badRequest("编辑时不能变更团队归属，请新增对应团队");
            id = input.id();
        }
        var existing = mapper.groupPeople(input.department(), input.team());
        var names = new HashMap<String, String>();
        for (var person : existing) names.put(text(person.get("user_id")), text(person.get("user_name")));
        var recipients = concat(input.to(), input.cc());
        var ids = recipients.stream().map(Recipient::userId).toList();
        if (!ids.isEmpty()) {
            // 长任务组织搜索和公共台账均可作为来源，也允许保留历史导入人员。
            for (var person : ledger.findByUserIds(ids))
                names.put(text(person.get("user_id")), text(person.get("user_name")));
            names.putAll(org.getUserNameMap(ids));
        }
        for (var person : recipients)
            if (!names.containsKey(person.userId())) throw badRequest("人员不存在，请重新搜索后选择：" + person.userId());
        if (ids.isEmpty()) mapper.deleteGroupPeople(input.department(), input.team());
        else mapper.deleteRemovedPeople(input.department(), input.team(), ids);
        savePeople(input, input.to(), "TO", names);
        savePeople(input, input.cc(), "CC", names);
        mapper.bumpGroupVersion(id);
    }

    private void savePeople(GroupInput group, List<Recipient> people, String type, Map<String, String> names) {
        for (int index = 0; index < people.size(); index++) {
            var person = people.get(index);
            var row = new HashMap<String, Object>();
            row.put("dept", group.department());
            row.put("team", group.team());
            row.put("user", person.userId());
            row.put("name", required(names.get(person.userId()), "姓名", 200));
            row.put("role", person.positionRole());
            row.put("enabled", person.enabled());
            row.put("remark", person.remark());
            row.put("type", type);
            row.put("sortOrder", index);
            if (mapper.updateGroupPerson(row) == 0) mapper.insert(row);
        }
    }

    @Transactional("gaussCustomerTransactionManager")
    public void deleteGroup(long id, long version) {
        var row = locked(id, version);
        mapper.deleteGroupPeople(text(row.get("dev_dept")), text(row.get("stat_group")));
        mapper.deleteGroup(id);
    }

    private Map<String, Object> locked(long id, Long version) {
        var row = mapper.lockGroup(id);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "团队配置不存在");
        if (version == null || version != number(row, "version"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "团队配置已被修改，请刷新列表后重新编辑");
        return row;
    }

    private static List<Recipient> recipients(List<Recipient> people) {
        if (people == null || people.size() > 500) throw badRequest("通知名单须为数组，且最多 500 人");
        return people.stream().map(person -> {
            if (person == null) throw badRequest("通知人员不能为空");
            return new Recipient(required(person.userId(), "统一认证号", 128), person.name(),
                limited(person.positionRole(), "岗位角色", 100), !Boolean.FALSE.equals(person.enabled()),
                limited(person.remark(), "人员备注", 10000));
        }).toList();
    }

    private static List<Recipient> concat(List<Recipient> to, List<Recipient> cc) {
        var result = new ArrayList<>(to);
        result.addAll(cc);
        return result;
    }

    private static String text(Object value) { return Objects.toString(value, "").trim(); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static String limited(String value, String label, int length) {
        String result = text(value);
        if (result.length() > length) throw badRequest(label + "过长");
        return result;
    }
    private static String required(String value, String label, int length) {
        String result = limited(value, label, length);
        if (result.isEmpty()) throw badRequest(label + "不能为空");
        return result;
    }
    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
