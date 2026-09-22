package com.agentscopea2a.v2.dimension;

import com.agentscopea2a.v2.dimension.DimensionState.PeerDimensionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 维度同义词解析器（纯确定性匹配，零 LLM，docs/dimension-alias-config-plan.md §4.2）。
 *
 * <p>算法五步：
 * <ol>
 *   <li>收集候选：对全部 enabled alias 做 contains 扫描；带 triggerKeyword 的行是条件行，
 *       仅当问题包含该完整关键词时参与；</li>
 *   <li>最长匹配优先：按 span 长度降序贪心 accept，与已 accept span 重叠的丢弃；</li>
 *   <li>同 span 定维度：a.触发词命中的行胜 → b.单维度 → c.无歧义优先 → d.固定兜底序
 *       PRODUCT_LINE &gt; TEAM &gt; APPLICATION；</li>
 *   <li>维度内候选数 &gt; 1 ⇒ ambiguous（走反问流程，不允许静默选一个）；</li>
 *   <li>产出 {@link AliasResolution}（resolved 按 span start 升序）。</li>
 * </ol>
 *
 * <p>线程安全：快照只读，单例 bean 可并发调用。
 */
public class AliasResolver {

    /**
     * 跨维度同词且无触发词可分时的固定兜底序（方案 §4.2 规则 d，业务 2026/09/21 拍板）。
     * 顺序：产品线 &gt; 小组 &gt; 应用。
     */
    private static final List<PeerDimensionType> FALLBACK_ORDER =
            List.of(PeerDimensionType.PRODUCT_LINE, PeerDimensionType.TEAM, PeerDimensionType.APPLICATION);

    private final Supplier<List<DimensionAlias>> loader;
    private volatile List<DimensionAlias> indexedSnapshot = List.of();
    private volatile List<List<Entry>> indexedByAliasLength = List.of();

    public AliasResolver(Supplier<List<DimensionAlias>> loader) {
        this.loader = loader;
    }

    /** 解析结果：resolved 与 ambiguous 互斥（同一 span 只进一边）。 */
    public record AliasResolution(List<ResolvedAlias> resolved, List<AmbiguousAlias> ambiguous) {

        public static final AliasResolution EMPTY = new AliasResolution(List.of(), List.of());

        public boolean hasAmbiguity() {
            return !ambiguous.isEmpty();
        }
    }

    /** 唯一命中：standardName 已是标准名。 */
    public record ResolvedAlias(PeerDimensionType dimension, String alias, String standardName, int start, int end) {}

    /** 同维度一对多：进反问流程。 */
    public record AmbiguousAlias(
            PeerDimensionType dimension, String alias, List<String> candidates, int start, int end) {}

    private record Entry(PeerDimensionType dimension, String alias, String standardName, String triggerKeyword) {}

    private record Hit(Entry entry, int start, int end) {
        int length() {
            return end - start;
        }
    }

    public AliasResolution resolve(String question) {
        if (question == null || question.isBlank()) {
            return AliasResolution.EMPTY;
        }
        List<List<Entry>> entries = snapshot();
        if (entries.isEmpty()) {
            return AliasResolution.EMPTY;
        }

        // 1. 收集候选（条件行按 entry 逐行过滤：triggerKeyword 未在问题中出现则该行不参与）
        List<Hit> hits = new ArrayList<>();
        for (List<Entry> sameAlias : entries) {
            String alias = sameAlias.get(0).alias();
            int from = 0;
            int idx;
            while ((idx = question.indexOf(alias, from)) >= 0) {
                for (Entry e : sameAlias) {
                    if (e.triggerKeyword() == null || question.contains(e.triggerKeyword())) {
                        hits.add(new Hit(e, idx, idx + alias.length()));
                    }
                }
                from = idx + 1;
            }
        }
        if (hits.isEmpty()) {
            return AliasResolution.EMPTY;
        }

        // 2. 最长匹配优先：按 span 分组后长度降序贪心 accept，与已 accept span 重叠的整组丢弃。
        //    同一 span 的全部命中（跨维度同词 / 同维度多行候选）保留到步骤 3 竞争。
        Map<String, List<Hit>> bySpan = new LinkedHashMap<>();
        for (Hit h : hits) {
            bySpan.computeIfAbsent(h.start() + ":" + h.end(), k -> new ArrayList<>()).add(h);
        }
        List<List<Hit>> spanGroups = new ArrayList<>(bySpan.values());
        spanGroups.sort(Comparator.comparingInt(g -> -(g.get(0).end() - g.get(0).start())));
        List<List<Hit>> accepted = new ArrayList<>();
        for (List<Hit> group : spanGroups) {
            int start = group.get(0).start();
            int end = group.get(0).end();
            boolean overlaps = false;
            for (List<Hit> a : accepted) {
                if (start < a.get(0).end() && a.get(0).start() < end) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                accepted.add(group);
            }
        }
        // 按 start 升序组织，保证 resolved 顺序与问题语序一致
        accepted.sort(Comparator.comparingInt(g -> g.get(0).start()));

        // 3-5. 同 span 定维度
        List<ResolvedAlias> resolved = new ArrayList<>();
        List<AmbiguousAlias> ambiguous = new ArrayList<>();
        for (List<Hit> sameSpan : accepted) {
            resolveSpan(question, sameSpan, resolved, ambiguous);
        }
        return new AliasResolution(List.copyOf(resolved), List.copyOf(ambiguous));
    }

    /**
     * 精确等值查表（供 LLM 回答提取值的归一化）：value 等于某 alias 且该 alias 只指向
     * 一个标准名时返回该标准名；歧义（一对多）或未命中返回 empty，调用方保留原值。
     */
    public java.util.Optional<ResolvedAlias> lookupExact(String value) {
        String v = value == null ? null : value.trim();
        if (v == null || v.isEmpty()) {
            return java.util.Optional.empty();
        }
        for (List<Entry> group : snapshot()) {
            if (!group.get(0).alias().equals(v)) {
                continue;
            }
            Set<String> standards = new LinkedHashSet<>();
            group.forEach(e -> standards.add(e.standardName()));
            if (standards.size() != 1) {
                return java.util.Optional.empty();
            }
            Entry e = group.get(0);
            return java.util.Optional.of(new ResolvedAlias(e.dimension(), e.alias(), e.standardName(), -1, -1));
        }
        return java.util.Optional.empty();
    }

    private void resolveSpan(
            String question, List<Hit> sameSpan,
            List<ResolvedAlias> resolved, List<AmbiguousAlias> ambiguous) {
        String alias = sameSpan.get(0).entry().alias();
        int start = sameSpan.get(0).start();
        int end = sameSpan.get(0).end();

        // a. 触发词命中的行直接胜（更具体的说法赢更笼统的）
        List<Hit> triggered = sameSpan.stream().filter(h -> h.entry().triggerKeyword() != null).toList();
        if (!triggered.isEmpty()) {
            emit(triggered, resolved, ambiguous);
            return;
        }

        // b. 只有一个维度有该 alias（其余维度是条件行未触发）⇒ 该维度
        Set<PeerDimensionType> dims = new LinkedHashSet<>();
        sameSpan.forEach(h -> dims.add(h.entry().dimension()));
        if (dims.size() == 1) {
            emit(sameSpan, resolved, ambiguous);
            return;
        }

        // c. 无歧义优先：唯一单候选的维度胜（"国库"：产品线单候选 vs 应用双候选 ⇒ 产品线）
        List<Hit> unambiguousDims = new ArrayList<>();
        for (PeerDimensionType dim : dims) {
            List<Hit> dimHits = sameSpan.stream().filter(h -> h.entry().dimension() == dim).toList();
            if (distinctStandards(dimHits).size() == 1) {
                unambiguousDims.add(dimHits.get(0));
            }
        }
        if (unambiguousDims.size() == 1) {
            emit(List.of(unambiguousDims.get(0)), resolved, ambiguous);
            return;
        }

        // d. 固定兜底序（选中维度内部若仍多候选，emit 会转 ambiguous 反问）
        for (PeerDimensionType dim : FALLBACK_ORDER) {
            if (!dims.contains(dim)) continue;
            List<Hit> dimHits = sameSpan.stream().filter(h -> h.entry().dimension() == dim).toList();
            emit(dimHits, resolved, ambiguous);
            return;
        }
        emit(sameSpan, resolved, ambiguous);
    }

    /**
     * 同维度内候选数 &gt; 1 ⇒ ambiguous；否则 resolved。
     * 同维度多个 alias 变体指向同一标准名时去重（如 银企/银企团队）。
     */
    private void emit(List<Hit> hits, List<ResolvedAlias> resolved, List<AmbiguousAlias> ambiguous) {
        Hit probe = hits.get(0);
        List<String> standards = distinctStandards(hits);
        if (standards.size() > 1) {
            ambiguous.add(new AmbiguousAlias(
                    probe.entry().dimension(), probe.entry().alias(), standards, probe.start(), probe.end()));
        } else {
            resolved.add(new ResolvedAlias(
                    probe.entry().dimension(), probe.entry().alias(), standards.get(0), probe.start(), probe.end()));
        }
    }

    private static List<String> distinctStandards(List<Hit> hits) {
        Set<String> set = new LinkedHashSet<>();
        hits.forEach(h -> set.add(h.entry().standardName()));
        return List.copyOf(set);
    }

    /**
     * 加载快照并按 alias 分组、组内按 alias 长度降序排列（长 alias 先扫，配合步骤 2；
     * 同 alias 的多行聚合在同一组，共享一次 indexOf 扫描）。
     */
    private List<List<Entry>> snapshot() {
        List<DimensionAlias> fresh = loader.get();
        if (fresh != indexedSnapshot) {
            Map<String, List<Entry>> byAlias = new LinkedHashMap<>();
            for (DimensionAlias row : fresh) {
                if (row == null || !row.enabled() || row.alias() == null || row.alias().isEmpty()
                        || row.standardName() == null || row.standardName().isEmpty()) {
                    continue;
                }
                byAlias.computeIfAbsent(row.alias(), k -> new ArrayList<>())
                        .add(new Entry(row.dimension(), row.alias(), row.standardName(), row.triggerKeyword()));
            }
            List<List<Entry>> groups = new ArrayList<>(byAlias.values());
            groups.sort(Comparator.comparingInt((List<Entry> g) -> g.get(0).alias().length()).reversed());
            indexedSnapshot = fresh;
            indexedByAliasLength = List.copyOf(groups);
        }
        return indexedByAliasLength;
    }
}
