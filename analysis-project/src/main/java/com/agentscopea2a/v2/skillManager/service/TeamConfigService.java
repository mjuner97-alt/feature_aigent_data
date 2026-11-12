package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.common.PageResponse;
import com.agentscopea2a.mapper.gaussCommon.TeamLedgerMapper;
import com.agentscopea2a.v2.skillManager.mapper.TeamConfigMapper;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 团队报告默认来源维护；人员台账只读，使用公共库最新统计月份的记录。 */
@Service
public class TeamConfigService {
    // 默认通知人员写入业务库，公共库台账只用于查找人员。
    private final TeamConfigMapper config;
    private final TeamLedgerMapper ledger;

    public TeamConfigService(TeamConfigMapper config, TeamLedgerMapper ledger) {
        this.config = config;
        this.ledger = ledger;
    }

    /** 分页查询业务库已保存的默认通知人员。 */
    public PageResponse<Map<String, Object>> list(String keyword, String department, String team, int page, int pageSize) {
        String term = trimmed(keyword), dept = trimmed(department), group = trimmed(team);
        int p = Math.max(1, page), size = Math.max(1, Math.min(100, pageSize));
        int total = config.count(term, dept, group);
        return new PageResponse<>(config.list(term, dept, group, size, ((long) p - 1) * size), p, size, total);
    }

    /** 列出已保存配置中的部门和统计组。 */
    public List<Map<String, Object>> options() {
        return config.options();
    }

    /** 列出公共库人员候选中的部门和统计组。 */
    public List<Map<String, Object>> peopleOptions() {
        return ledger.options();
    }

    /** 按姓名、认证号和组织条件分页查询去重后的人员台账。 */
    public PageResponse<Map<String, Object>> people(String keyword, String department, String team, int page, int pageSize) {
        String term = trimmed(keyword), dept = trimmed(department), group = trimmed(team);
        int p = Math.max(1, page), size = Math.max(1, Math.min(100, pageSize));
        int total = ledger.count(term, dept, group);
        return new PageResponse<>(ledger.list(term, dept, group, size, ((long) p - 1) * size), p, size, total);
    }

    /** 将空参数归一为空字符串并清除首尾空白。 */
    private static String trimmed(String value) {
        return Objects.toString(value, "").trim();
    }

    /** 读取配置详情，记录不存在时返回 404。 */
    public Map<String, Object> get(long id) {
        var row = config.get(id);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "团队参数不存在");
        return row;
    }

    /** 新增和编辑接口的团队配置请求。 */
    public record Input(String dev_dept, String stat_group, String user_id, String user_name,
                        String position_role, String recipient_type, Integer sort_order, Boolean enabled,
                        String remark, String report_keyword, String notify_contact_name, String notify_contact_phone) {}

    /** 批量导入时提交的人员认证号。 */
    public record BatchInput(List<String> userIds) {}
    /** 本次实际新增及因已存在而跳过的人数。 */
    public record BatchResult(int added, int skipped) {}
    /** 单独修改主送或抄送类型的请求。 */
    public record RecipientTypeInput(String recipientType) {}

    /** 校验通知类型并更新已有配置。 */
    @Transactional("gaussCustomerTransactionManager")
    public Map<String, Object> updateRecipientType(long id, RecipientTypeInput input) {
        String type = required(input == null ? null : input.recipientType(), "通知类型", 2);
        if (!Set.of("TO", "CC").contains(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知类型须为 TO 或 CC");
        int updated = config.updateRecipientType(id, type);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "团队参数不存在");
        return get(id);
    }

    /** 从公共库查出选中人员，在业务库事务内批量导入。 */
    @Transactional("gaussCustomerTransactionManager")
    public BatchResult addFromLedger(BatchInput input) {
        if (input == null || input.userIds() == null || input.userIds().isEmpty() || input.userIds().size() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择 1 至 500 位人员");
        var ids = input.userIds().stream().map(id -> required(id, "统一认证号", 128)).distinct().toList();
        var people = ledger.findByUserIds(ids);
        if (people.size() != ids.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部分人员已不在最新月份的台账中，请重新查询后选择");
        // 全部校验通过后再写入，部门与岗位均以台账为准。
        var inputs = people.stream().map(person -> {
            String role = limited(Objects.toString(person.get("position_role"), ""), 100);
            return new Input(required(Objects.toString(person.get("dev_dept"), ""), "部门", 200),
                limited(Objects.toString(person.get("stat_group"), ""), 200),
                required(Objects.toString(person.get("user_id"), ""), "统一认证号", 128),
                required(Objects.toString(person.get("user_name"), ""), "姓名", 200), role,
                "分管领导".equals(role) ? "CC" : "TO", null, true, "", null, null, null);
        }).toList();
        int added = 0;
        for (var row : inputs) {
            if (config.countExisting(row.dev_dept(), row.stat_group(), row.user_id()) > 0) continue;
            save(null, row);
            added++;
        }
        return new BatchResult(added, ids.size() - added);
    }

    /** 校验并保存默认通知人员，空 ID 表示新增。 */
    @Transactional("gaussCustomerTransactionManager")
    public Map<String, Object> save(Long id, Input input) {
        String dept = required(input.dev_dept(), "部门", 200);
        String user = required(input.user_id(), "统一认证号", 128);
        String name = required(input.user_name(), "姓名", 200);
        String type = required(input.recipient_type(), "通知类型", 2);
        if (!Set.of("TO", "CC").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知类型须为 TO 或 CC");
        var row = new HashMap<String, Object>();
        row.put("id", id);
        row.put("dept", dept);
        row.put("team", limited(input.stat_group(), 200));
        row.put("user", user);
        row.put("name", name);
        row.put("role", limited(input.position_role(), 100));
        row.put("type", type);
        // 分管领导优先排序，其余人员统一使用排序值 1。
        row.put("sortOrder", "分管领导".equals(row.get("role")) ? 0 : 1);
        row.put("enabled", !Boolean.FALSE.equals(input.enabled()));
        row.put("remark", input.remark());
        try {
            if (id == null) {
                config.insert(row);
                return get(((Number) row.get("id")).longValue());
            }
            int updated = config.update(row);
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "团队参数不存在");
            return get(id);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该部门、团队下已存在此人员，请编辑已有记录");
        }
    }

    /** 校验必填字段，同时限制长度。 */
    private static String required(String value, String label, int max) {
        String result = limited(value, max);
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
        return result;
    }

    /** 去除首尾空白并校验字段长度。 */
    private static String limited(String value, int max) {
        String result = Objects.toString(value, "").trim();
        if (result.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段长度不能超过 " + max);
        return result;
    }

    /** 按主键删除配置，记录不存在时返回 404。 */
    public void delete(long id) {
        if (config.delete(id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "团队参数不存在");
    }
}
