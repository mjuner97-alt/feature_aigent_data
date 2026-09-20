package com.agentscopea2a.v2.skillManager.flowcache;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 节点缓存 DAO:唯一落库点,直接 JDBC(表 skill_flow_node_cache,GaussDB)。
 *
 * <p>抢占语义(claim):依赖 cache_key 的 UNIQUE 约束做乐观锁——
 * INSERT 成功即抢占;撞唯一键说明已有人占着,走 takeover:
 * <ul>
 *   <li>普通抢占:仅当该行非 READY 且锁已过期(上次执行者疑似挂掉)才能接管;</li>
 *   <li>refresh 强制抢占:无视状态与锁直接接管,用于"强制重算"。</li>
 * </ul>
 *
 * <p>时间口径统一用应用时钟(skillFlowClock):锁时间的写入与锁过期的判断必须同源,
 * 混用 DB 的 CURRENT_TIMESTAMP 会在应用机与 DB 时钟有偏差时误判锁状态。
 */
@Repository
public class FlowNodeCacheRepository {
    private final DataSource dataSource;
    private final java.time.Clock clock;

    public FlowNodeCacheRepository(@Qualifier("gaussCustomerDataSource") DataSource dataSource, @Qualifier("skillFlowClock") java.time.Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /**
     * 按 cache_key 精确查一行(UNIQUE,至多一条)。
     */
    public Optional<FlowNodeCache> find(String key) {
        String sql = "SELECT id,cache_key,node_id,node_version,result_path,checksum,status,lock_owner,lock_expire_at,business_date,expire_at,result_content FROM skill_flow_node_cache WHERE cache_key=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, key);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("node cache read failed", e);
        }
    }

    /**
     * 抢占:INSERT 一条 BUILDING 行。
     *
     * @param lockUntil 软锁到期时间(执行超时兜底,执行者崩溃后他人可接管)
     * @param expireAt  整条缓存的过期时间(命中有效期)
     * @param refresh   true = 强制重算,唯一键冲突时无视状态/锁直接接管
     * @return true = 抢占成功(拿到 lockOwner);false = 已有活跃抢占者
     */
    public boolean claim(String key, String nodeId, String version, LocalDate date, String owner, LocalDateTime lockUntil, LocalDateTime expireAt, boolean refresh) {
        String sql = "INSERT INTO skill_flow_node_cache(cache_key,node_id,node_version,status,lock_owner,lock_expire_at,business_date,expire_at) VALUES(?,?,?,'BUILDING',?,?,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, key);
            p.setString(2, nodeId);
            p.setString(3, version);
            p.setString(4, owner);
            p.setTimestamp(5, Timestamp.valueOf(lockUntil));
            p.setDate(6, Date.valueOf(date));
            p.setTimestamp(7, Timestamp.valueOf(expireAt));
            p.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (isDuplicate(e)) return takeover(key, owner, lockUntil, expireAt, refresh);
            throw new IllegalStateException("node cache claim failed", e);
        }
    }

    // refresh=true forces takeover of any existing row (incl. READY), so the rebuilt result can be persisted;
    // otherwise only a non-READY row with an expired lock may be taken over.
    // 接管时同时续期 expire_at:从旧行(INVALID/过期)重建的缓存不能继承旧的过期时间。
    private boolean takeover(String key, String owner, LocalDateTime until, LocalDateTime expireAt, boolean force) {
        String cond = force ? "cache_key=?" : "cache_key=? AND status<>'READY' AND (lock_expire_at IS NULL OR lock_expire_at < ?)";
        String sql = "UPDATE skill_flow_node_cache SET status='BUILDING',lock_owner=?,lock_expire_at=?,expire_at=?,updated_at=CURRENT_TIMESTAMP WHERE " + cond;
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, owner);
            p.setTimestamp(2, Timestamp.valueOf(until));
            p.setTimestamp(3, Timestamp.valueOf(expireAt));
            p.setString(4, key);
            if (!force) p.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now(clock))); // 锁过期判断与应用时钟同源
            return p.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 执行成功:结果全文入库(result_content),清锁,置 READY。仅 lock_owner 匹配才生效。
     * 同时清空 result_path(历史文件方案遗留列),内容以 result_content 为准。
     */
    public void ready(String key, String owner, String content, String checksum) {
        update("UPDATE skill_flow_node_cache SET status='READY',result_content=?,checksum=?,result_path=NULL,lock_owner=NULL,lock_expire_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE cache_key=? AND lock_owner=?", content, checksum, key, owner);
    }

    /**
     * 标记条目不可用(文件校验失败/已过期),下次 claim 可立即接管。
     */
    public void invalid(String key) {
        update("UPDATE skill_flow_node_cache SET status='INVALID',updated_at=CURRENT_TIMESTAMP WHERE cache_key=?", key);
    }

    /**
     * 执行失败:仅当自己仍是该 BUILDING 行的锁持有者时删除,防止误删他人的接管。
     */
    public void delete(String key, String owner) {
        update("DELETE FROM skill_flow_node_cache WHERE cache_key=? AND lock_owner=? AND status='BUILDING'", key, owner);
    }

    /**
     * 删除已过期的行,返回删除条数。结果内容存在行内(result_content),删行即删内容。
     */
    public int cleanup(LocalDateTime cutoff) {
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement("DELETE FROM skill_flow_node_cache WHERE expire_at < ?")) {
            p.setTimestamp(1, Timestamp.valueOf(cutoff));
            return p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * update 帮助方法:参数仅支持 String(当前所有调用方均满足)。
     */
    private void update(String sql, Object... a) {
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < a.length; i++) {
                if (a[i] instanceof String) p.setString(i + 1, (String) a[i]);
            }
            p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private FlowNodeCache read(ResultSet r) throws SQLException {
        return new FlowNodeCache(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getTimestamp(9) == null ? null : r.getTimestamp(9).toLocalDateTime(), r.getDate(10).toLocalDate(), r.getTimestamp(11).toLocalDateTime(), r.getString(12));
    }

    private boolean isDuplicate(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith("23");
    }
}
