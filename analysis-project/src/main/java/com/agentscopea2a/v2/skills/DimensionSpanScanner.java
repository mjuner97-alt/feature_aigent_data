package com.agentscopea2a.v2.skills;

import com.agentscopea2a.v2.dimension.DimensionAlias;
import com.agentscopea2a.v2.dimension.DimensionStateManager;
import com.agentscopea2a.v2.dimension.PersonLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 维度实体 span 扫描器（docs/202610/检索关键词与维度值冲突治理方案.md §4）：
 * 输入 normalized 问题文本，输出"维度实体指称" span 集，供
 * {@link SkillCandidateSelector} 把落在实体内部的词条命中判为假主题信号。
 *
 * <p>span 来源：S1 alias 口语列；S2 标准名列（alias standardName + 台账真值字典
 * 统计组/产品线/应用 distinct）；S3 组名 token（{@code [一-龥A-Za-z0-9]{2,}组}，
 * 未配 alias 的组名兜底）；S4 时间 span（{@code DimensionStateManager.timeShieldSpans}）。
 *
 * <p>词表快照 Supplier + TTL + fail-open：读失败/空词表 → 空 span 集 = 屏蔽自动关闭，
 * 行为回退现状。所有词归一化走 {@link SkillTermMatcher#normalize}，坐标基于
 * normalized 文本。
 */
public class DimensionSpanScanner {

    private static final Logger log = LoggerFactory.getLogger(DimensionSpanScanner.class);

    /** S3 组名 token（与 DimensionStateManager.EXPLICIT_TEAM 同式）。 */
    static final Pattern TEAM_TOKEN = Pattern.compile("[一-龥A-Za-z0-9]{2,}组");

    /** 单字实体词造成的屏蔽面过大（且无歧义价值），跳过。 */
    static final int MIN_ENTITY_WORD_LENGTH = 2;

    private final Supplier<List<DimensionAlias>> aliasSupplier;
    private final Supplier<PersonLedgerRepository> ledgerSupplier;
    private final long ttlMillis;

    private volatile List<String> cachedWords = List.of();
    private volatile long cachedAt = 0L;
    private volatile boolean loaded = false;

    /** Supplier 注入口（单测免 DB）。 */
    public DimensionSpanScanner(Supplier<List<DimensionAlias>> aliasSupplier,
                                Supplier<PersonLedgerRepository> ledgerSupplier,
                                long ttlMillis) {
        this.aliasSupplier = aliasSupplier;
        this.ledgerSupplier = ledgerSupplier;
        this.ttlMillis = Math.max(1_000L, ttlMillis);
    }

    /**
     * 实体 span 集（normalized 文本上的 [start,end)，已合并重叠）。任何失败 →
     * 空/陈旧快照（fail-open），不抛错不阻塞检索。
     */
    public List<int[]> scan(String normalizedQuestion) {
        if (normalizedQuestion == null || normalizedQuestion.isEmpty()) {
            return List.of();
        }
        List<int[]> spans = new ArrayList<>();
        for (String word : entityWords()) {
            int idx = normalizedQuestion.indexOf(word);
            while (idx >= 0) {
                spans.add(new int[] {idx, idx + word.length()});
                idx = normalizedQuestion.indexOf(word, idx + 1);
            }
        }
        Matcher m = TEAM_TOKEN.matcher(normalizedQuestion);
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
        }
        try {
            spans.addAll(DimensionStateManager.timeShieldSpans(normalizedQuestion));
        } catch (RuntimeException e) {
            log.debug("DimensionSpanScanner: time spans failed (ignored): {}", e.getMessage());
        }
        return merge(spans);
    }

    /** 实体词表（normalized，去重）：TTL 内用缓存；失败时用陈旧缓存，无缓存则空集。 */
    private List<String> entityWords() {
        long now = System.currentTimeMillis();
        if (loaded && now - cachedAt < ttlMillis) {
            return cachedWords;
        }
        synchronized (this) {
            if (loaded && System.currentTimeMillis() - cachedAt < ttlMillis) {
                return cachedWords;
            }
            try {
                cachedWords = loadWords();
                cachedAt = System.currentTimeMillis();
                loaded = true;
            } catch (RuntimeException e) {
                if (loaded) {
                    log.warn("DimensionSpanScanner: refresh failed, keeping stale snapshot: {}", e.getMessage());
                } else {
                    log.warn("DimensionSpanScanner: load failed, span shield disabled (fail-open): {}", e.getMessage());
                    cachedWords = List.of();
                    cachedAt = System.currentTimeMillis();
                    loaded = true;
                }
            }
            return cachedWords;
        }
    }

    private List<String> loadWords() {
        Set<String> words = new LinkedHashSet<>();
        List<DimensionAlias> aliases = aliasSupplier.get();
        if (aliases != null) {
            for (DimensionAlias row : aliases) {
                if (row == null || !row.enabled()) continue;
                addWord(words, row.alias());
                addWord(words, row.standardName());
            }
        }
        PersonLedgerRepository ledger = ledgerSupplier == null ? null : ledgerSupplier.get();
        if (ledger != null) {
            for (String column : List.of("统计组", "产品线", "应用")) {
                Set<String> truth = ledger.dimensionTruthDictionary(column);
                if (truth != null) {
                    truth.forEach(v -> addWord(words, v));
                }
            }
        }
        return List.copyOf(words);
    }

    private static void addWord(Set<String> words, String raw) {
        if (raw == null) return;
        String w = SkillTermMatcher.normalize(raw);
        if (w.length() < MIN_ENTITY_WORD_LENGTH) return;
        words.add(w);
    }

    /** 重叠区间合并（按 start 排序后一遍并）。 */
    static List<int[]> merge(List<int[]> spans) {
        if (spans.size() <= 1) return spans;
        List<int[]> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingInt(s -> s[0]));
        List<int[]> out = new ArrayList<>();
        int start = sorted.get(0)[0];
        int end = sorted.get(0)[1];
        for (int i = 1; i < sorted.size(); i++) {
            int[] s = sorted.get(i);
            if (s[0] <= end) {
                end = Math.max(end, s[1]);
            } else {
                out.add(new int[] {start, end});
                start = s[0];
                end = s[1];
            }
        }
        out.add(new int[] {start, end});
        return out;
    }

    /** 命中 [start,end) 是否落在任一 span 内（span 少，线性即可）。 */
    public static boolean inSpans(int start, int end, List<int[]> mergedSpans) {
        for (int[] s : mergedSpans) {
            if (start < s[1] && s[0] < end) {
                return true;
            }
        }
        return false;
    }
}
