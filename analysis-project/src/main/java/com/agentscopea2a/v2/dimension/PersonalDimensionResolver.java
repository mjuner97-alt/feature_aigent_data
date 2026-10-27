/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.v2.dimension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "我X" 口语维度解析（人员台账维度方案 §3）：代词结构正则，凭统一认证号查台账
 * {@link PersonLedgerRepository} 解析归属。
 *
 * <p>2026/10/26 我X方案 §8：MY_* 正则全部改为「硬编码兜底 + dimension_key_config
 * regex 型条目（type=regex, value=用途标签）叠加判定」，配置走 findAllEnabled TTL
 * 快照再包 60s 短缓存，前端补条目热生效；岗位角色方案 §3.1b/§3.1d 提供双路径角色提取
 * （路径A extract 正则 / 路径B Q4 字典扫描+人锚共存）。
 *
 * <p>产物供两条路径消费：直接执行路径在 {@code DirectSkillExecutor.plan} 做 extraction 级
 * enrich（主场景，§3.3）；LLM 回退路径在 DimensionStateMiddleware 写 DimensionState（§3.3b）。
 *
 * <p>降级契约（§3.5）：userId 空 / 台账查无此人 / 查询失败 → 命中标志保留但 ledger=null，
 * 调用方不注入任何维度。
 */
public class PersonalDimensionResolver {

    private static final Logger log = LoggerFactory.getLogger(PersonalDimensionResolver.class);

    // 我部 / 我们部门 / 我的部门 / 本部门 / 我所在部门
    private static final Pattern MY_DEPT =
            Pattern.compile("我部|我们部门|我的部门|本部门|我所在部门");
    // 我的组 / 我们组 / 我的团队 / 我们团队 / 我的小组 / 我的统计组（仍无裸"我组"，避"我组装"误伤）
    private static final Pattern MY_GROUP =
            Pattern.compile("我的组|我们组|我的团队|我们团队|我的小组|我的统计组");
    private static final Pattern MY_APP = Pattern.compile("我的应用(群组)?");
    private static final Pattern MY_PRODUCT = Pattern.compile("我的(产品线|条线|业务线)");
    // 裸"我"（§3.6 误伤约束）：前面非中文、后面非中文且不吃 我们/的/组/们 ——
    // "我的部门/我们组" 等复合词由具体维度正则承接；裸"我们"不触发任何注入
    private static final Pattern MYSELF = Pattern.compile("(?<![一-龥])我(个人|本人)?(?![一-龥组们的])");
    // myself 变体兜底（我X方案 §8.1 perNo 行 seed 同款）：补 MYSELF 负向断言对"我担任"的缺口
    private static final Pattern MYSELF_VARIANTS = Pattern.compile("我担任|我现任|我所担任");
    // 路径A 角色提取兜底（岗位角色方案 §3.1b perRole 行 seed 同款）：第一捕获组 = 角色值
    private static final Pattern ROLE_EXTRACT_DEFAULT =
            Pattern.compile("担任([一-龥]{2,10}?)(?=[的，。？?！!、\\s]|$)");

    private final PersonLedgerRepository ledgerRepository;
    private final ObjectProvider<DimensionKeyConfigRepository> keyConfigProvider;

    private static final long PATTERNS_TTL_MILLIS = 60_000L;

    /** 配置正则快照（我X方案 §8.2）：findAllEnabled TTL 快照上再包一层 60s 短缓存；
     *  各列表为空 = 该标签无配置条目（调用方用硬编码兜底）。 */
    private record ConfiguredPatterns(List<Pattern> myDept, List<Pattern> myGroup,
                                      List<Pattern> myApp, List<Pattern> myProduct,
                                      List<Pattern> myselfVariants, List<Pattern> roleExtract) {
        static final ConfiguredPatterns EMPTY =
                new ConfiguredPatterns(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private volatile ConfiguredPatterns configured;
    private volatile long configuredAt;

    public PersonalDimensionResolver(PersonLedgerRepository ledgerRepository) {
        this(ledgerRepository, null);
    }

    public PersonalDimensionResolver(PersonLedgerRepository ledgerRepository,
                                     ObjectProvider<DimensionKeyConfigRepository> keyConfigProvider) {
        this.ledgerRepository = ledgerRepository;
        this.keyConfigProvider = keyConfigProvider;
    }

    /** regex 型条目 → 按用途标签分组的编译快照；compile 失败跳过该条（其余照用）。 */
    private ConfiguredPatterns configuredPatterns() {
        ConfiguredPatterns cached = configured;
        long now = System.currentTimeMillis();
        if (cached != null && now - configuredAt < PATTERNS_TTL_MILLIS) {
            return cached;
        }
        ConfiguredPatterns fresh = loadConfiguredPatterns();
        configured = fresh;
        configuredAt = now;
        return fresh;
    }

    private ConfiguredPatterns loadConfiguredPatterns() {
        if (keyConfigProvider == null) {
            return ConfiguredPatterns.EMPTY;
        }
        DimensionKeyConfigRepository repo = keyConfigProvider.getIfAvailable();
        if (repo == null) {
            return ConfiguredPatterns.EMPTY;
        }
        List<DimensionKeyConfig> rows;
        try {
            rows = repo.findAllEnabled();
        } catch (RuntimeException e) {
            log.warn("PersonalDimensionResolver: 维度键字典读取失败, 我X正则走硬编码兜底: {}", e.getMessage());
            return ConfiguredPatterns.EMPTY;
        }
        Map<String, List<Pattern>> byTag = new LinkedHashMap<>();
        for (DimensionKeyConfig row : rows) {
            for (DimensionKeyConfig.AliasMapping alias : row.aliases()) {
                if (!alias.valid() || !alias.isRegex()) {
                    continue;
                }
                try {
                    byTag.computeIfAbsent(alias.value().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(Pattern.compile(alias.alias()));
                } catch (Exception e) {
                    log.warn("PersonalDimensionResolver: 非法 regex 条目跳过 key={} value={} alias={}: {}",
                            row.key(), alias.value(), alias.alias(), e.getMessage());
                }
            }
        }
        return new ConfiguredPatterns(
                byTag.getOrDefault("mydept", List.of()),
                byTag.getOrDefault("mygroup", List.of()),
                byTag.getOrDefault("myapp", List.of()),
                byTag.getOrDefault("myproductline", List.of()),
                byTag.getOrDefault("myself", List.of()),
                byTag.getOrDefault("extract", List.of()));
    }

    private static boolean anyHit(Pattern baseline, List<Pattern> extra, String question) {
        if (baseline.matcher(question).find()) {
            return true;
        }
        for (Pattern p : extra) {
            if (p.matcher(question).find()) {
                return true;
            }
        }
        return false;
    }

    /** 解析结果：命中标志 + 台账归属（查无此人/userId 空时为 null，§3.5）。 */
    public record PersonalResolution(
            boolean myDept, boolean myGroup, boolean myApp, boolean myProductLine, boolean myself,
            PersonLedgerRepository.PersonLedgerRow ledger) {

        public boolean anyHit() {
            return myDept || myGroup || myApp || myProductLine || myself;
        }
    }

    /** resolve 的空结果常量（问题未命中任何口语词）。 */
    public static PersonalResolution none() {
        return new PersonalResolution(false, false, false, false, false, null);
    }

    /**
     * 解析问题里的"我X"口语词并查台账。命中具体维度（我部/我组/我的应用/我的产品线）
     * 时不再触发 MYSELF（§3.6：先查具体维度后查自身）。正则 = 硬编码兜底 ∪ 配置条目
     * （我X方案 §8.2 叠加判定，任一命中即成立）。
     */
    public PersonalResolution resolve(String question, String userId) {
        if (question == null || question.isBlank()) {
            return none();
        }
        ConfiguredPatterns cfg = configuredPatterns();
        boolean myDept = anyHit(MY_DEPT, cfg.myDept(), question);
        boolean myGroup = anyHit(MY_GROUP, cfg.myGroup(), question);
        boolean myApp = anyHit(MY_APP, cfg.myApp(), question);
        boolean myProduct = anyHit(MY_PRODUCT, cfg.myProduct(), question);
        boolean myself = !myDept && !myGroup && !myApp && !myProduct
                && anyHit(MYSELF, cfg.myselfVariants(), question);
        if (!myDept && !myGroup && !myApp && !myProduct && !myself) {
            return none();
        }
        PersonLedgerRepository.PersonLedgerRow ledger = null;
        if (userId == null || userId.isBlank()) {
            log.debug("PersonalDimensionResolver: 口语命中但 userId 为空, 不注入");
        } else {
            ledger = ledgerRepository.byUserId(userId);
            if (ledger == null) {
                log.info("PersonalDimensionResolver: userId={} 台账查无此人/查询失败, 不注入", userId);
            }
        }
        return new PersonalResolution(myDept, myGroup, myApp, myProduct, myself, ledger);
    }

    /**
     * §4.1 人名字典扫描：屏蔽文本（{@code DimensionStateManager.maskForPersonScan}）上
     * 穷举 2~4 字窗口（步进 1，最长优先），精确命中姓名字典即为姓名候选。
     * 字典为空（台账不可用）→ 整体关闭返回空。
     */
    public List<String> scanNames(String maskedQuestion) {
        Set<String> dictionary = ledgerRepository.nameDictionary();
        if (maskedQuestion == null || maskedQuestion.isBlank() || dictionary.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int n = maskedQuestion.length();
        for (int i = 0; i < n - 1; i++) {
            int maxLen = Math.min(4, n - i);
            for (int len = maxLen; len >= 2; len--) {
                String window = maskedQuestion.substring(i, i + len);
                if (dictionary.contains(window)) {
                    if (!out.contains(window)) {
                        out.add(window);
                    }
                    i += len - 1; // 最长命中消费整段，窗口越过
                    break;
                }
            }
        }
        return out;
    }

    /**
     * §4.2 按姓名解析身份（Q2 + 统一认证号去重）：单一身份 → 直填 perNo/perName；
     * 多个身份（重名）→ 调用方反问；空（字典/台账不一致或查询失败）→ 跳过。
     */
    public List<PersonLedgerRepository.PersonIdentity> resolveName(String name) {
        return ledgerRepository.byName(name);
    }

    /** 台账仓储访问（Q5-Q7 维度真值字典的调用方入口，维度值台账真值校验方案 §4）。 */
    public PersonLedgerRepository ledgerRepository() {
        return ledgerRepository;
    }

    /**
     * B① 真值域正向扫描（维度值台账真值校验方案 §4.3）：真值域值逐个在问题**原文**
     * indexOf（最长优先 + boolean[] span 消费防重叠，同路径B角色扫描模式），命中即收录
     * 标准值——不依赖正则形状，「杭州二部FMBM资金交易组」「软负载均衡团队」型不规则
     * 值域照样命中（§10 案例二/四）。最短 2 字防单字误命中。真值域空 → 空列表。
     */
    public List<String> scanTruthValues(String question, Set<String> truthSet) {
        if (question == null || question.isBlank()
                || truthSet == null || truthSet.isEmpty()) {
            return List.of();
        }
        List<String> values = truthSet.stream()
                .filter(v -> v != null && v.length() >= 2)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        boolean[] consumed = new boolean[question.length()];
        List<String> out = new ArrayList<>();
        for (String value : values) {
            int from = 0;
            int idx;
            while ((idx = question.indexOf(value, from)) >= 0) {
                boolean free = true;
                for (int i = idx; i < idx + value.length(); i++) {
                    if (consumed[i]) {
                        free = false;
                        break;
                    }
                }
                if (free) {
                    for (int i = idx; i < idx + value.length(); i++) {
                        consumed[i] = true;
                    }
                    if (!out.contains(value)) {
                        out.add(value);
                    }
                    break;
                }
                from = idx + 1;
            }
        }
        return List.copyOf(out);
    }

    // ==================== 岗位角色双路径提取（岗位角色内置维度方案 §3.1b/§3.1d） ====================

    /**
     * 路径A 关键字提取：perRole 配置 extract 正则（兜底=担任...）逐条 find，取第一捕获组
     * → Q4 角色字典包含关系归一（字典值 ⊇ 捕获 或 反向 → 收字典标准值，最长优先）。
     * 字典不可用 → 捕获值原样收（fail-open，脚本侧自查）。
     */
    public List<String> extractRolesByPattern(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        ConfiguredPatterns cfg = configuredPatterns();
        List<Pattern> extractors = cfg.roleExtract().isEmpty()
                ? List.of(ROLE_EXTRACT_DEFAULT) : cfg.roleExtract();
        Set<String> out = new LinkedHashSet<>();
        for (Pattern p : extractors) {
            Matcher m = p.matcher(question);
            while (m.find()) {
                if (m.groupCount() >= 1 && m.group(1) != null && !m.group(1).isBlank()) {
                    out.add(normalizeRole(m.group(1).trim()));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * 路径B 字典扫描（无关键字直连，"张三项目经理"型）：Q4 角色字典值逐个在问句
     * indexOf（最长优先消歧 + 命中 span 屏蔽防重叠），命中即角色值。
     * <p><b>人锚共存闸门</b>：personAnchor=false（无 myself/人名扫描命中）直接返回空——
     * "应用开发人员的达标率"（§7-4 无具体人按角色查询）不收，边界不破。
     * 字典空/查询失败 → 空（fail-safe，路径B 自然禁用）。
     */
    public List<String> extractRolesByDictionary(String question, boolean personAnchor) {
        if (!personAnchor || question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> dictionary = ledgerRepository.roleDictionary();
        if (dictionary.isEmpty()) {
            return List.of();
        }
        List<String> sorted = dictionary.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        boolean[] taken = new boolean[question.length()];
        List<int[]> hitSpans = new ArrayList<>();
        List<String> hitValues = new ArrayList<>();
        for (String value : sorted) {
            int from = 0;
            int idx;
            while ((idx = question.indexOf(value, from)) >= 0) {
                int end = idx + value.length();
                boolean overlap = false;
                for (int i = idx; i < end; i++) {
                    if (taken[i]) {
                        overlap = true;
                        break;
                    }
                }
                if (!overlap) {
                    hitSpans.add(new int[]{idx, end});
                    hitValues.add(value);
                    for (int i = idx; i < end; i++) {
                        taken[i] = true;
                    }
                }
                from = end;
            }
        }
        // 按问句出现位置排序（最长优先的字典序会打乱自然语序）
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < hitValues.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> hitSpans.get(i)[0]));
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i : order) {
            out.add(hitValues.get(i));
        }
        return List.copyOf(out);
    }

    /** 捕获值 → 字典标准值（包含关系双向命中，最长字典值优先）；字典无命中/不可用 → 原样收。 */
    private String normalizeRole(String captured) {
        Set<String> dictionary = ledgerRepository.roleDictionary();
        if (dictionary.isEmpty()) {
            return captured;
        }
        if (dictionary.contains(captured)) {
            return captured;
        }
        String best = null;
        for (String role : dictionary) {
            if ((role.contains(captured) || captured.contains(role))
                    && (best == null || role.length() > best.length())) {
                best = role;
            }
        }
        return best != null ? best : captured;
    }
}
