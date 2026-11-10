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
    private static final String COLUMNS = "id,dev_dept,stat_group,user_id,user_name,position_role,recipient_type,sort_order,enabled,remark,report_keyword,notify_contact_name,notify_contact_phone,created_at,updated_at";
    static final String SNAPSHOT = " FROM developer_pl_person_info_t WHERE \"统计月份\"=(SELECT MAX(\"统计月份\") FROM developer_pl_person_info_t) AND \"有效日期截止\"='9999-12-31'";

    public TeamConfigService(@Qualifier("gaussCustomerDataSource") DataSource config,
                             @Qualifier("gaussCommonDataSource") DataSource ledger) {
        this.config = new JdbcTemplate(config);
        this.ledger = new JdbcTemplate(ledger);
    }

    public PageResponse<Map<String, Object>> list(String keyword, int page, int pageSize) {
        String where = " WHERE (dev_dept LIKE ? OR stat_group LIKE ? OR user_name LIKE ? OR user_id LIKE ?)";
        String term = "%" + Objects.toString(keyword, "").trim() + "%";
        return page(config, "SELECT " + COLUMNS + " FROM " + TABLE + where,
            "SELECT COUNT(*) FROM " + TABLE + where, List.of(term, term, term, term),
            "dev_dept,stat_group,sort_order,id", Objects.toString(keyword, "").trim(), page, pageSize);
    }

    public PageResponse<Map<String, Object>> people(String keyword, int page, int pageSize) {
        String where = SNAPSHOT + " AND LENGTH(TRIM(\"统一认证号\"))>0 AND (\"姓名\" LIKE ? OR \"统一认证号\" LIKE ? OR \"部门\" LIKE ? OR \"统计组\" LIKE ?)";
        String term = "%" + Objects.toString(keyword, "").trim() + "%";
        // 同一人员的有效记录仍可能重复，稳定取 id 最大的一条，再分页。
        String source = " FROM (SELECT id,\"部门\" AS dev_dept,\"统计组\" AS stat_group,\"统一认证号\" AS user_id,\"姓名\" AS user_name,\"岗位角色\" AS position_role,\"统计月份\" AS stat_month,ROW_NUMBER() OVER(PARTITION BY \"统一认证号\" ORDER BY id DESC) AS rn" + where + ") p WHERE rn=1";
        return page(ledger, "SELECT id,dev_dept,stat_group,user_id,user_name,position_role,stat_month" + source,
            "SELECT COUNT(*)" + source, List.of(term, term, term, term), "user_id,id", Objects.toString(keyword, "").trim(), page, pageSize);
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

    @Transactional("gaussCustomerTransactionManager")
    public Map<String, Object> save(Long id, Input input) {
        String dept = required(input.dev_dept(), "部门", 200);
        String user = required(input.user_id(), "统一认证号", 128);
        String name = required(input.user_name(), "姓名", 200);
        String type = required(input.recipient_type(), "通知类型", 2);
        if (!Set.of("TO", "CC").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知类型须为 TO 或 CC");
        Object[] values = {dept, limited(input.stat_group(), 200), user, name, limited(input.position_role(), 100),
            type, input.sort_order() == null ? 0 : input.sort_order(), !Boolean.FALSE.equals(input.enabled()),
            input.remark(), limited(input.report_keyword(), 500), limited(input.notify_contact_name(), 200), limited(input.notify_contact_phone(), 32)};
        try {
            if (id == null) {
                var keys = new GeneratedKeyHolder();
                config.update(connection -> {
                    var statement = connection.prepareStatement("INSERT INTO " + TABLE + "(dev_dept,stat_group,user_id,user_name,position_role,recipient_type,sort_order,enabled,remark,report_keyword,notify_contact_name,notify_contact_phone) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", new String[]{"id"});
                    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                    return statement;
                }, keys);
                return get(Objects.requireNonNull(keys.getKey()).longValue());
            }
            var args = new ArrayList<>(Arrays.asList(values));
            args.add(id);
            int updated = config.update("UPDATE " + TABLE + " SET dev_dept=?,stat_group=?,user_id=?,user_name=?,position_role=?,recipient_type=?,sort_order=?,enabled=?,remark=?,report_keyword=?,notify_contact_name=?,notify_contact_phone=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", args.toArray());
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
