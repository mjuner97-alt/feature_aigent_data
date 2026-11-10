package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.common.PageResponse;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 团队报告默认来源维护；人员台账只读，使用公共库的最新有效快照。 */
@Service
public class TeamConfigService {
    private final JdbcTemplate config;
    private final JdbcTemplate ledger;
    private static final String TABLE = "long_task_report_notify_person";
    private static final String COLUMNS = "id,dev_dept,stat_group,user_id,user_name,position_role,recipient_type,sort_order,enabled,remark,created_at,updated_at";
    static final String SNAPSHOT = " FROM developer_pl_person_info_t WHERE \"统计月份\"=(SELECT MAX(\"统计月份\") FROM developer_pl_person_info_t) AND \"有效日期截止\"='9999-12-31'";
    private static final String PEOPLE_SOURCE = " FROM (SELECT id,\"部门\" AS dev_dept,\"统计组\" AS stat_group,\"统一认证号\" AS user_id,\"姓名\" AS user_name,\"岗位角色\" AS position_role,\"统计月份\" AS stat_month,ROW_NUMBER() OVER(PARTITION BY \"统一认证号\" ORDER BY id DESC) AS rn"
        + SNAPSHOT + " AND LENGTH(TRIM(\"统一认证号\"))>0) p WHERE rn=1";

    public TeamConfigService(@Qualifier("gaussCustomerDataSource") DataSource config,
                             @Qualifier("gaussCommonDataSource") DataSource ledger) {
        this.config = new JdbcTemplate(config);
        this.ledger = new JdbcTemplate(ledger);
    }

    public PageResponse<Map<String, Object>> list(String keyword, String department, String team, int page, int pageSize) {
        String where = " WHERE (user_name LIKE ? OR user_id LIKE ?)";
        String term = "%" + Objects.toString(keyword, "").trim() + "%";
        var args = new ArrayList<Object>(List.of(term, term));
        if (department != null && !department.isBlank()) {
            where += " AND dev_dept=?";
            args.add(department.trim());
        }
        if (team != null && !team.isBlank()) {
            where += " AND stat_group=?";
            args.add(team.trim());
        }
        return page(config, "SELECT " + COLUMNS + " FROM " + TABLE + where,
            "SELECT COUNT(*) FROM " + TABLE + where, args,
            "dev_dept,stat_group,sort_order,id", Objects.toString(keyword, "").trim(), page, pageSize);
    }

    public List<Map<String, Object>> options() {
        return config.queryForList("SELECT DISTINCT dev_dept,stat_group FROM " + TABLE
            + " WHERE dev_dept IS NOT NULL AND LENGTH(TRIM(dev_dept))>0 ORDER BY dev_dept,stat_group");
    }

    public List<Map<String, Object>> peopleOptions() {
        return ledger.queryForList("SELECT DISTINCT dev_dept,stat_group" + PEOPLE_SOURCE
            + " AND dev_dept IS NOT NULL AND LENGTH(TRIM(dev_dept))>0 ORDER BY dev_dept,stat_group");
    }

    public PageResponse<Map<String, Object>> people(String keyword, String department, String team, int page, int pageSize) {
        String source = PEOPLE_SOURCE + " AND (user_name LIKE ? OR user_id LIKE ?)";
        String term = "%" + Objects.toString(keyword, "").trim() + "%";
        var args = new ArrayList<Object>(List.of(term, term));
        if (department != null && !department.isBlank()) {
            source += " AND dev_dept=?";
            args.add(department.trim());
        }
        if (team != null && !team.isBlank()) {
            source += " AND stat_group=?";
            args.add(team.trim());
        }
        return page(ledger, "SELECT id,dev_dept,stat_group,user_id,user_name,position_role,stat_month" + source,
            "SELECT COUNT(*)" + source, args, "user_id,id", Objects.toString(keyword, "").trim(), page, pageSize);
    }

    private PageResponse<Map<String, Object>> page(JdbcTemplate jdbc, String select, String count,
            List<Object> args, String order, String exactUser, int page, int pageSize) {
        int p = Math.max(1, page), size = Math.max(1, Math.min(100, pageSize));
        Integer total = jdbc.queryForObject(count, Integer.class, args.toArray());
        var values = new ArrayList<>(args);
        values.add(exactUser);
        values.add(size);
        values.add(((long) p - 1) * size);
        return new PageResponse<>(jdbc.queryForList(select + " ORDER BY CASE WHEN user_id=? THEN 0 ELSE 1 END," + order + " LIMIT ? OFFSET ?", values.toArray()),
            p, size, total == null ? 0 : total);
    }

    public Map<String, Object> get(long id) {
        var rows = config.queryForList("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "团队参数不存在");
        return rows.get(0);
    }

    public record Input(String dev_dept, String stat_group, String user_id, String user_name,
                        String position_role, String recipient_type, Integer sort_order, Boolean enabled,
                        String remark, String report_keyword, String notify_contact_name, String notify_contact_phone) {}

    public record BatchInput(List<String> userIds) {}
    public record BatchResult(int added, int skipped) {}
    public record RecipientTypeInput(String recipientType) {}

    @Transactional("gaussCustomerTransactionManager")
    public Map<String, Object> updateRecipientType(long id, RecipientTypeInput input) {
        String type = required(input == null ? null : input.recipientType(), "通知类型", 2);
        if (!Set.of("TO", "CC").contains(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知类型须为 TO 或 CC");
        int updated = config.update("UPDATE " + TABLE + " SET recipient_type=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", type, id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "团队参数不存在");
        return get(id);
    }

    @Transactional("gaussCustomerTransactionManager")
    public BatchResult addFromLedger(BatchInput input) {
        if (input == null || input.userIds() == null || input.userIds().isEmpty() || input.userIds().size() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择 1 至 500 位人员");
        var ids = input.userIds().stream().map(id -> required(id, "统一认证号", 128)).distinct().toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        var people = ledger.queryForList("SELECT dev_dept,stat_group,user_id,user_name,position_role" + PEOPLE_SOURCE
            + " AND user_id IN (" + placeholders + ") ORDER BY user_id", ids.toArray());
        if (people.size() != ids.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部分人员已不在最新有效台账中，请重新查询后选择");
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
            Integer exists = config.queryForObject("SELECT COUNT(*) FROM " + TABLE
                + " WHERE dev_dept=? AND stat_group=? AND user_id=?", Integer.class,
                row.dev_dept(), row.stat_group(), row.user_id());
            if (exists != null && exists > 0) continue;
            save(null, row);
            added++;
        }
        return new BatchResult(added, ids.size() - added);
    }

    @Transactional("gaussCustomerTransactionManager")
    public Map<String, Object> save(Long id, Input input) {
        String dept = required(input.dev_dept(), "部门", 200);
        String user = required(input.user_id(), "统一认证号", 128);
        String name = required(input.user_name(), "姓名", 200);
        String type = required(input.recipient_type(), "通知类型", 2);
        if (!Set.of("TO", "CC").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知类型须为 TO 或 CC");
        Object[] values = {dept, limited(input.stat_group(), 200), user, name, limited(input.position_role(), 100),
            type, "分管领导".equals(Objects.toString(input.position_role(), "").trim()) ? 0 : 1, !Boolean.FALSE.equals(input.enabled()),
            input.remark()};
        try {
            if (id == null) {
                var keys = new GeneratedKeyHolder();
                config.update(connection -> {
                    var statement = connection.prepareStatement("INSERT INTO " + TABLE + "(dev_dept,stat_group,user_id,user_name,position_role,recipient_type,sort_order,enabled,remark) VALUES(?,?,?,?,?,?,?,?,?)", new String[]{"id"});
                    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                    return statement;
                }, keys);
                return get(Objects.requireNonNull(keys.getKey()).longValue());
            }
            var args = new ArrayList<>(Arrays.asList(values));
            args.add(id);
            int updated = config.update("UPDATE " + TABLE + " SET dev_dept=?,stat_group=?,user_id=?,user_name=?,position_role=?,recipient_type=?,sort_order=?,enabled=?,remark=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", args.toArray());
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "团队参数不存在");
            return get(id);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该部门、团队下已存在此人员，请编辑已有记录");
        }
    }

    private static String required(String value, String label, int max) {
        String result = limited(value, max);
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
        return result;
    }

    private static String limited(String value, int max) {
        String result = Objects.toString(value, "").trim();
        if (result.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段长度不能超过 " + max);
        return result;
    }

    public void delete(long id) {
        if (config.update("DELETE FROM " + TABLE + " WHERE id=?", id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "团队参数不存在");
    }
}
