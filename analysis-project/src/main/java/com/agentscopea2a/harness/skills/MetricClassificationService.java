/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.harness.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentscopea2a.agent.model.ModelUtil;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 指标语义分类服务 — 方案 B 核心组件。
 *
 * <p>在 PreCall bump 后异步调用轻量 LLM，对用户问题做指标分类（如 defect_density /
 * response_time / error_rate ...），结果写入 {@code skill_candidate.metric_tag} 列。
 *
 * <p>每个 fingerprint 只分类一次（{@code WHERE metric_tag IS NULL} 过滤），节省成本。
 * 调用走 {@code boundedElastic} 线程池，不阻塞 PreCall 热路径。
 *
 * <p>指标分类配置从 {@code workspace/knowledge/metric-categories.yaml} 加载，
 * 业务方可随时扩展新指标类别，无需改代码。配置文件缺失时回退到内置默认值。
 */
@Service
public class MetricClassificationService {

    private static final Logger log = LoggerFactory.getLogger(MetricClassificationService.class);

    private final SkillCandidateRepository candidateRepo;
    private volatile Model lightModel;
    private final boolean enabled;
    private final String modelInstanceName;
    private final Path workspacePath;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * In-flight dedup: track fingerprints currently being classified.
     */
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();

    // --- Config-driven state, populated by @PostConstruct ---
    private List<MetricCategoryConfig> categories = List.of();
    private Map<String, String> metricHints = Map.of();
    private Set<String> validTags = Set.of();
    private String fallbackTag = "general";
    private String fallbackDescription = "does not match any above category";
    private String llmCategoriesBlock = "";

    // --- Compiled regex patterns for keyword matching ---
    private List<CompiledKeyword> compiledKeywords = List.of();

    public MetricClassificationService(
            SkillCandidateRepository candidateRepo,
            @Value("${harness.skills.metric-classification.enabled:true}") boolean enabled,
            @Value("${harness.skills.metric-classification.model-instance:light-classifier}") String modelInstance,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath) {
        this.candidateRepo = candidateRepo;
        this.enabled = enabled;
        this.workspacePath = Path.of(workspacePath);
        // Defer ModelUtil.get() to @PostConstruct — ModelUtil may not be initialized during
        // constructor injection (bean creation order is not guaranteed). FingerprintCalculator
        // only needs ruleBasedTag() which is pure keyword matching, so the bean can be created
        // before the light model is available.
        this.modelInstanceName = modelInstance;
        this.lightModel = null;  // resolved in init()
    }

    public boolean enabled() {
        return enabled && lightModel != null;
    }

    /**
     * Returns the Chinese hint for a metric tag, used by SkillDistiller to enrich the
     * distillation prompt. Returns the raw tag if no hint is configured.
     */
    public String getMetricHint(String metricTag) {
        if (metricTag == null || metricTag.isBlank()) return "";
        return metricHints.getOrDefault(metricTag, metricTag);
    }

    // ==================== Config loading ====================

    @PostConstruct
    void init() {
        // Resolve light model after Spring context is fully initialized
        if (enabled) {
            try {
                this.lightModel = ModelUtil.get(modelInstanceName);
            } catch (Exception e) {
                log.warn("MetricClassificationService: ModelUtil not available yet ({}), will retry on first use",
                        e.getMessage());
            }
            if (lightModel == null) {
                log.warn("MetricClassificationService enabled but model instance '{}' not found; "
                        + "LLM classification disabled, rule-based classification still active",
                        modelInstanceName);
            } else {
                log.info("MetricClassificationService initialized with model instance '{}'", modelInstanceName);
            }
        }
        try {
            Path configFile = workspacePath.resolve("knowledge").resolve("metric-categories.yaml");
            if (Files.exists(configFile)) {
                loadConfig(configFile);
            } else {
                log.info("metric-categories.yaml not found at {}, using built-in defaults", configFile);
                applyDefaults();
            }
        } catch (Exception e) {
            log.warn("Failed to load metric categories, using built-in defaults: {}", e.getMessage());
            applyDefaults();
        }
    }

    private void loadConfig(Path configFile) throws Exception {
        // Use YAMLMapper (from jackson-dataformat-yaml) instead of plain ObjectMapper,
        // because ObjectMapper defaults to JSON parsing and cannot handle YAML comments (#)
        // or YAML structure. Spring Boot includes jackson-dataformat-yaml transitively.
        com.fasterxml.jackson.dataformat.yaml.YAMLMapper yamlMapper =
                new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
        String content = Files.readString(configFile);
        MetricCategoriesYaml yaml = yamlMapper.readValue(content, MetricCategoriesYaml.class);
        applyConfig(yaml);
        log.info("Loaded {} metric categories from {}", yaml.categories.size(), configFile);
    }

    private void applyConfig(MetricCategoriesYaml yaml) {
        this.categories = yaml.categories != null ? yaml.categories : List.of();
        this.fallbackTag = yaml.fallbackTag != null ? yaml.fallbackTag : "general";
        this.fallbackDescription = yaml.fallbackDescription != null ? yaml.fallbackDescription : "does not match any above category";

        // Build derived structures
        this.metricHints = categories.stream()
                .collect(Collectors.toMap(
                        MetricCategoryConfig::tag,
                        MetricCategoryConfig::chineseHint,
                        (a, b) -> a));
        this.validTags = categories.stream()
                .map(MetricCategoryConfig::tag)
                .collect(Collectors.toSet());
        validTags.add(this.fallbackTag);

        // Compile keyword patterns
        this.compiledKeywords = categories.stream()
                .flatMap(cat -> cat.keywords.stream()
                        .map(kw -> new CompiledKeyword(kw, cat.tag, kw.startsWith("\\") || kw.contains("\\b"))))
                .toList();

        // Build LLM prompt categories block
        StringBuilder sb = new StringBuilder();
        for (MetricCategoryConfig cat : categories) {
            sb.append("- ").append(cat.tag).append(": ").append(cat.description).append('\n');
        }
        sb.append("- ").append(fallbackTag).append(": ").append(fallbackDescription).append('\n');
        this.llmCategoriesBlock = sb.toString();
    }

    private void applyDefaults() {
        MetricCategoriesYaml yaml = builtInDefaults();
        applyConfig(yaml);
        log.info("Using {} built-in metric categories", yaml.categories.size());
    }

    // ==================== Classification ====================

    /**
     * 异步分类 + 回填 metric_tag。
     */
    public void classifyAndUpdateAsync(String question, String fingerprint) {
        if (!enabled() || question == null || question.isBlank() || fingerprint == null) {
            return;
        }
        if (!inflight.add(fingerprint)) {
            log.debug("[METRIC_CLASSIFY] skipping in-flight classification for {}", fingerprint);
            return;
        }
        try {
            Mono.fromRunnable(() -> doClassifyAndUpdate(question, fingerprint))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorComplete()
                    .subscribe(
                            v -> {},
                            ex -> log.debug("Async metric classification failed for {}: {}", fingerprint, ex.getMessage()));
        } finally {
            inflight.remove(fingerprint);
        }
    }

    private void doClassifyAndUpdate(String question, String fingerprint) {
        String tag = ruleBasedTag(question);
        log.info("[METRIC_CLASSIFY] fingerprint={} question='{}' ruleTag={} (willUseLLM={})",
                fingerprint, question, tag, tag == null);
        if (tag == null) {
            tag = doClassify(question);
        }
        if (tag == null || tag.isBlank()) {
            return;
        }
        candidateRepo.updateMetricTag(fingerprint, tag);
    }

    /**
     * Rule-based keyword match, driven by the config file.
     * Categories are checked in config order (priority order).
     * Keywords starting with {@code \} are treated as regex patterns; all others use contains().
     */
    public String ruleBasedTag(String question) {
        if (question == null || question.isBlank()) return null;
        String q = question.toLowerCase();
        if (compiledKeywords.isEmpty()) {
            log.warn("[METRIC_CLASSIFY] compiledKeywords is EMPTY — categories not loaded, question='{}'", question);
            return null;
        }
        for (CompiledKeyword ck : compiledKeywords) {
            if (ck.regex) {
                if (ck.pattern.matcher(q).find()) {
                    log.debug("[METRIC_CLASSIFY] regex match: keyword='{}' tag='{}' question='{}'", ck.keyword, ck.tag, question);
                    return ck.tag;
                }
            } else {
                String kw = ck.keyword.toLowerCase();
                if (q.contains(kw)) {
                    log.info("[METRIC_CLASSIFY] keyword match: keyword='{}' tag='{}' question='{}'", kw, ck.tag, question);
                    return ck.tag;
                }
            }
        }
        log.debug("[METRIC_CLASSIFY] no keyword match for question='{}'", question);
        return null;
    }

    private String doClassify(String question) {
        String prompt = buildPrompt(question);
        Msg msg = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(prompt).build())
                .build();
        try {
            GenerateOptions options = GenerateOptions.builder()
                    .maxTokens(200)
                    .temperature(0.0)
                    .build();
            String response = lightModel.stream(List.of(msg), List.of(), options)
                    .reduce(new StringBuilder(), MetricClassificationService::appendChunk)
                    .map(StringBuilder::toString)
                    .block();
            log.debug("Metric classification response: {}", response);
            return parseTag(response);
        } catch (Exception ex) {
            log.debug("LLM call failed for metric classification: {}", ex.getMessage());
            return null;
        }
    }

    private String buildPrompt(String question) {
        return """
                Classify the following user question into one of these metric categories.

                User question (Chinese): %s

                Categories:
                %s
                Rules:
                1. If the question contains keywords from multiple categories, prefer the specific metric (e.g., defect_density) over stat_summary
                2. Return ONLY the English label, nothing else

                Label:""".formatted(question, llmCategoriesBlock);
    }

    private String parseTag(String response) {
        if (response == null || response.isBlank()) return null;
        String cleaned = response.trim().toLowerCase();
        int newlineIdx = cleaned.indexOf('\n');
        String firstLine = (newlineIdx > 0 ? cleaned.substring(0, newlineIdx) : cleaned).trim();
        firstLine = firstLine.replaceAll("[^a-z0-9_]", "");
        if (validTags.contains(firstLine)) {
            return firstLine;
        }
        // Fallback: scan full response for known tags in priority order
        for (MetricCategoryConfig cat : categories) {
            if (cleaned.contains(cat.tag)) return cat.tag;
        }
        return fallbackTag;
    }

    private static StringBuilder appendChunk(StringBuilder sb, io.agentscope.core.model.ChatResponse resp) {
        if (resp == null || resp.getContent() == null) return sb;
        for (var block : resp.getContent()) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb;
    }

    // ==================== Inner types ====================

    /** Compiled keyword for rule-based matching. */
    private record CompiledKeyword(String keyword, String tag, boolean regex, Pattern pattern) {
        CompiledKeyword(String keyword, String tag, boolean regex) {
            this(keyword, tag, regex, regex ? Pattern.compile(keyword, Pattern.CASE_INSENSITIVE) : null);
        }
    }

    /** YAML mapping for the config file. */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MetricCategoryConfig(
            String tag,
            String chineseHint,
            List<String> keywords,
            String description
    ) {}

    /** Top-level YAML structure. */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MetricCategoriesYaml(
            List<MetricCategoryConfig> categories,
            String fallbackTag,
            String fallbackDescription
    ) {}

    // ==================== Built-in defaults ====================

    private static MetricCategoriesYaml builtInDefaults() {
        return new MetricCategoriesYaml(List.of(
                new MetricCategoryConfig("defect_density", "缺陷密度/Bug密度",
                        List.of("缺陷密度", "bug密度", "缺陷率", "defect_density", "defect density"),
                        "questions about 缺陷密度, Bug密度, 缺陷率, defect density, bug density"),
                new MetricCategoryConfig("response_time", "响应时间/延迟/RT",
                        List.of("\\brt\\b", "响应时间", "耗时", "延迟", "latency"),
                        "questions about 响应时间, RT, 耗时, 延迟, response time, latency"),
                new MetricCategoryConfig("error_rate", "错误率/失败率/异常率",
                        List.of("错误率", "失败率", "异常率", "error_rate", "error rate", "failure rate"),
                        "questions about 错误率, 失败率, 异常率, error rate, failure rate"),
                new MetricCategoryConfig("throughput", "吞吐量/TPS/QPS/并发",
                        List.of("吞吐量", "tps", "qps", "并发", "throughput"),
                        "questions about 吞吐量, TPS, QPS, 并发, throughput"),
                new MetricCategoryConfig("availability", "可用性/SLA/稳定性",
                        List.of("可用性", "sla", "稳定性", "availability"),
                        "questions about 可用性, SLA, 稳定性, availability"),
                new MetricCategoryConfig("code_quality", "代码质量/圈复杂度/重复率",
                        List.of("代码质量", "圈复杂度", "重复率", "code_quality", "code quality"),
                        "questions about 代码质量, 圈复杂度, 重复率, code quality"),
                new MetricCategoryConfig("test_coverage", "测试覆盖率/覆盖率",
                        List.of("测试覆盖率", "覆盖率", "case覆盖率", "test_coverage", "test coverage"),
                        "questions about 测试覆盖率, 覆盖率, test coverage"),
                new MetricCategoryConfig("range_analysis", "极值范围分析(最大值/最小值/极差)",
                        List.of("最大值", "最小值", "极差", "波动", "range_analysis", "max", "min"),
                        "questions about 最大值, 最小值, 极差, 波动, max, min, range"),
                new MetricCategoryConfig("stat_summary", "通用统计汇总(均值/求和/中位数)",
                        List.of("均值", "平均值", "求和", "统计", "中位数", "标准差", "方差", "mean", "average", "sum", "median"),
                        "questions about 均值, 平均值, 求和, 统计, mean, average, sum")
        ), "general", "does not match any above category");
    }
}