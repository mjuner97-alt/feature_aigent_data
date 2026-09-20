package com.agentscopea2a.v2.skillManager.flowcache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.Optional;
import java.util.UUID;

/**
 * 节点结果缓存门面:skill 长任务流程中"同一节点 + 同一输入"的结果复用。
 *
 * <p>典型调用链(hit-or-miss + claim/build/finish):
 * <pre>
 *   Optional&lt;String&gt; cached = svc.hit(nodeId, version, input, params, businessDate, refresh);
 *   if (cached.isPresent()) return cached.get();          // ① 命中直接复用
 *   Claim c = svc.claim(nodeId, version, input, params, businessDate, refresh);
 *   if (c.won()) {                                        // ② 抢到锁才需要真正执行
 *     try {
 *       String result = execute();                        // ③ 耗时执行
 *       svc.success(c, result);                           // ④ 内容入库 + 置 READY
 *       return result;
 *     } catch (RuntimeException e) {
 *       svc.failure(c);                                   // ⑤ 失败回收 BUILDING 行
 *       throw e;
 *     }
 *   }
 *   return execute();                                    // ⑥ 没抢到锁:照常执行但不缓存
 * </pre>
 *
 * <p>设计原则:缓存永远是旁路,任何异常(表不存在、校验失败)都降级为
 * "当作没有缓存",绝不阻塞主流程;多实例并发时靠 cache_key 唯一约束 + 10 分钟软锁
 * 保证只有一个实例落缓存,没抢到的实例结果不落库、也不覆盖别人的结果。
 * 有效期 1 天(business_date 维度),过期后 hit 直接 miss。
 * 结果全文存在 skill_flow_node_cache.result_content 列内(不落磁盘文件),
 * 过期清理删行即删内容。
 */
@Service
public class FlowNodeCacheService {
    private static final Logger log = LoggerFactory.getLogger(FlowNodeCacheService.class);
    private final FlowNodeCacheRepository repo;
    private final FlowCacheKeyBuilder keys;
    private final Clock clock;

    public FlowNodeCacheService(FlowNodeCacheRepository repo, com.fasterxml.jackson.databind.ObjectMapper mapper, @org.springframework.beans.factory.annotation.Qualifier("skillFlowClock") Clock clock) {
        this.repo = repo;
        this.keys = new FlowCacheKeyBuilder(mapper);
        this.clock = clock;
    }

    /**
     * 查缓存:READY 且未过期且 result_content 非空才返回内容。
     *
     * <p>内容缺失(历史文件方案遗留行)→ 整条标 INVALID(下次可被立即重新抢占重建);
     * 已过期 → 同样标 INVALID,返回 miss。refresh=true 时直接 miss(强制重算)。
     *
     * @return 命中返回结果全文;miss/任何读异常返回 empty,调用方照常执行
     */
    public Optional<String> hit(String nodeId, String version, String input, Object params, LocalDate date, boolean refresh) {
        if (refresh) return Optional.empty();
        String key = keys.build(nodeId, version, input, params, date);
        try {
            Optional<FlowNodeCache> c = repo.find(key);
            if (c.isPresent() && "READY".equals(c.get().status())) {
                boolean expired = c.get().expireAt() == null || !c.get().expireAt().isAfter(LocalDateTime.now(clock));
                if (expired) {
                    repo.invalid(key);
                    log.info("node cache EXPIRED key={}", key);
                } else {
                    String content = c.get().resultContent();
                    if (content != null && !content.isEmpty()) {
                        log.info("node cache HIT key={}", key);
                        return Optional.of(content);
                    }
                    repo.invalid(key);
                    log.info("node cache INVALID(legacy row without content) key={}", key);
                }
            }
            log.info("node cache MISS key={}", key);
        } catch (RuntimeException e) {
            log.warn("node cache read bypassed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 抢占执行权:拿到才有资格执行并把结果写回缓存。
     *
     * <p>refresh=false:唯一键冲突时,仅当已有抢占者的锁已过期才能接管;
     * refresh=true:强制接管(包括 READY 行),保证重算结果能落库。
     * 抢占失败(won=false)调用方照常执行,但结果不落缓存。
     */
    public Claim claim(String nodeId, String version, String input, Object params, LocalDate date, boolean refresh) {
        String key = keys.build(nodeId, version, input, params, date);
        String owner = UUID.randomUUID().toString();
        try {
            boolean won = repo.claim(key, nodeId, version, date, owner, LocalDateTime.now(clock).plusMinutes(10), LocalDateTime.now(clock).plusDays(1), refresh);
            return new Claim(key, owner, won);
        } catch (RuntimeException e) {
            log.warn("node cache claim bypassed: {}", e.getMessage());
            return new Claim(key, owner, false);
        }
    }

    /**
     * 插入缓存
     * 执行成功:结果全文入库(result_content)+ 置 READY(带 lockOwner 校验,防误写他人接管的行)。
     * 未抢到锁的调用(won=false)直接 return,不覆盖抢占者的缓存内容。
     */
    public void success(Claim c, String result) {
        if (!c.won()) return;
        try {
            repo.ready(c.key(), c.owner(), result, sha256Hex(result));
        } catch (RuntimeException e) {
            log.warn("node cache write bypassed: {}", e.getMessage());
        }
    }

    /**
     * 执行失败:回收自己抢占的 BUILDING 行,让下次能重新抢占。
     * 锁已被他人接管时不删行(他人在执行,行归他)。
     */
    public void failure(Claim c) {
        if (c.won()) try {
            repo.delete(c.key(), c.owner());
        } catch (RuntimeException e) {
            log.warn("node cache failure cleanup bypassed: {}", e.getMessage());
        }
    }

    /**
     * claim 的返回:key + 本次抢占者标识 + 是否抢占成功(决定结果是否允许落缓存)。
     */
    public record Claim(String key, String owner, boolean won) {
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
