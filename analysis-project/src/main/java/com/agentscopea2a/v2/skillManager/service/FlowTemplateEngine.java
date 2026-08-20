package com.agentscopea2a.v2.skillManager.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill Flow 模板引擎:校验并渲染节点问题模板 / 汇总问题模板。
 *
 * <p>模板语言:用 {@code {变量名}} 占位,渲染时缺失变量会直接抛错(避免把占位符发给 AI)。
 * 节点模板可用变量:server_date、original_question、flow_name、skill_name、upstream_results;
 * 汇总模板可用变量:server_date、original_question、flow_name、all_results。</p>
 */
public class FlowTemplateEngine {

    /** 变量占位符,如 {server_date}。 */
    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]+)}");
    /** 节点模板允许的变量集合。 */
    private static final Set<String> NODE_VARIABLES = Set.of(
            "server_date", "original_question", "flow_name", "skill_name", "upstream_results");
    /** 汇总模板允许的变量集合。 */
    private static final Set<String> SUMMARY_VARIABLES = Set.of(
            "server_date", "original_question", "flow_name", "all_results");

    /** 模板校验结果:valid=false 时 errors 列出非法变量等原因。 */
    public record Validation(boolean valid, List<String> errors) {
        public Validation {
            errors = List.copyOf(errors);
        }
    }

    /** 渲染上下文:变量名 -> 变量值。 */
    public record Context(Map<String, String> values) {
        public Context {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    /** 校验节点问题模板。 */
    public Validation validateNodeTemplate(String template) {
        return validate(template, NODE_VARIABLES);
    }

    /** 校验汇总问题模板。 */
    public Validation validateSummaryTemplate(String template) {
        return validate(template, SUMMARY_VARIABLES);
    }

    /** 渲染模板:任一变量缺失或渲染结果为空都会抛出 IllegalArgumentException。 */
    public String render(String template, Context context) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("template must not be blank");
        }
        Map<String, String> values = context == null ? Map.of() : context.values();
        Matcher matcher = VARIABLE.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String variable = matcher.group(1).trim();
            String value = values.get(variable);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("template variable is unavailable: " + variable);
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output);
        String rendered = output.toString();
        if (rendered.isBlank()) {
            throw new IllegalArgumentException("rendered template must not be blank");
        }
        return rendered;
    }

    /** 校验模板非空且所有变量都在允许范围内。 */
    private Validation validate(String template, Set<String> allowedVariables) {
        List<String> errors = new ArrayList<>();
        if (template == null || template.isBlank()) {
            errors.add("template must not be blank");
            return new Validation(false, errors);
        }
        Set<String> variables = variablesIn(template);
        for (String variable : variables) {
            if (!allowedVariables.contains(variable)) {
                errors.add("template variable is unavailable in this scope: " + variable);
            }
        }
        return new Validation(errors.isEmpty(), errors);
    }

    /** 提取模板中出现的全部变量名。 */
    private Set<String> variablesIn(String template) {
        Matcher matcher = VARIABLE.matcher(template);
        Set<String> variables = new LinkedHashSet<>();
        while (matcher.find()) {
            variables.add(matcher.group(1).trim());
        }
        return variables;
    }
}
