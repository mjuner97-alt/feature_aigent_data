package com.agentscopea2a.v2.presentation;

import com.agentscopea2a.entity.PresentationTemplateEntry;
import com.agentscopea2a.v2.skillManager.report.HtmlReportRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves a database template without executing template-provided JavaScript. */
@Component
public class RegisteredPresentationTemplateRenderer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TEMPLATE_LENGTH = 1_000_000;
    private static final int MAX_VARIABLES_LENGTH = 2_000_000;
    private static final Pattern EXACT_BINDING = Pattern.compile("^\\{\\{([A-Za-z][A-Za-z0-9_.-]*)}}$");
    private static final Pattern BINDING = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_.-]*)}}");
    private static final Pattern SECTION = Pattern.compile(
            "\\{\\{#([A-Za-z][A-Za-z0-9_.-]*)}}([\\s\\S]*?)\\{\\{/\\1}}"
    );
    private static final Pattern UNSAFE_HTML = Pattern.compile(
            "(?is)<\\s*(script|iframe|object|embed|base)\\b|\\son[a-z]+\\s*=|javascript\\s*:|\\ssrcdoc\\s*=|<meta\\b[^>]*http-equiv"
    );

    private final HtmlReportRenderer htmlReportRenderer;

    public RegisteredPresentationTemplateRenderer(HtmlReportRenderer htmlReportRenderer) {
        this.htmlReportRenderer = htmlReportRenderer;
    }

    public Rendered render(PresentationTemplateEntry template, String variablesJson) {
        validateDefinition(template);
        if (variablesJson == null || variablesJson.isBlank()) {
            throw new IllegalArgumentException("variables 必填");
        }
        if (variablesJson.length() > MAX_VARIABLES_LENGTH) {
            throw new IllegalArgumentException("variables 超过 2MB 限制");
        }

        JsonNode variables = parseObject(variablesJson, "variables");
        validateVariables(template.getVariableSchema(), variables);

        String chartJson = hasText(template.getEchartsTemplate())
                ? renderEcharts(template.getEchartsTemplate(), variables)
                : null;
        String html = hasText(template.getHtmlTemplate())
                ? renderHtml(template.getHtmlTemplate(), variables)
                : null;

        String title = textVariable(variables, "title", template.getName());
        String document = assembleDocument(html, chartJson);
        return new Rendered(title, htmlReportRenderer.render(document, title));
    }

    public void validateDefinition(PresentationTemplateEntry template) {
        if (template == null) throw new IllegalArgumentException("展示模板不存在或已禁用");
        if (!hasText(template.getTemplateId())) throw new IllegalArgumentException("template_id 不能为空");
        if (!hasText(template.getName())) throw new IllegalArgumentException("name 不能为空");
        if (!hasText(template.getEchartsTemplate()) && !hasText(template.getHtmlTemplate())) {
            throw new IllegalArgumentException("echarts_template 与 html_template 不能同时为空");
        }
        checkLength(template.getEchartsTemplate(), "echarts_template");
        checkLength(template.getHtmlTemplate(), "html_template");
        if (hasText(template.getEchartsTemplate())) {
            parseObject(template.getEchartsTemplate(), "echarts_template");
        }
        if (hasText(template.getHtmlTemplate()) && UNSAFE_HTML.matcher(template.getHtmlTemplate()).find()) {
            throw new IllegalArgumentException("html_template 包含禁止的脚本、嵌入标签或事件属性");
        }
        validateSchemaDefinition(parseSchema(template.getVariableSchema()));
    }

    private String renderEcharts(String source, JsonNode root) {
        JsonNode resolved = resolveJsonNode(parseObject(source, "echarts_template"), root);
        String json;
        try {
            json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(resolved);
        } catch (Exception e) {
            throw new IllegalArgumentException("ECharts 模板序列化失败: " + e.getMessage(), e);
        }
        if (json.contains("{{")) throw new IllegalArgumentException("ECharts 模板存在未解析变量");
        return json;
    }

    private JsonNode resolveJsonNode(JsonNode node, JsonNode root) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> result.set(e.getKey(), resolveJsonNode(e.getValue(), root)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(value -> result.add(resolveJsonNode(value, root)));
            return result;
        }
        if (!node.isTextual()) return node.deepCopy();

        String text = node.textValue();
        Matcher exact = EXACT_BINDING.matcher(text);
        if (exact.matches()) {
            JsonNode value = resolvePath(root, exact.group(1));
            if (value == null || value.isMissingNode()) {
                throw new IllegalArgumentException("缺少模板变量: " + exact.group(1));
            }
            return value.deepCopy();
        }

        Matcher matcher = BINDING.matcher(text);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            JsonNode value = resolvePath(root, matcher.group(1));
            if (value == null || value.isContainerNode()) {
                throw new IllegalArgumentException("嵌入字符串的模板变量必须是标量: " + matcher.group(1));
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value.asText()));
        }
        matcher.appendTail(rendered);
        return MAPPER.getNodeFactory().textNode(rendered.toString());
    }

    private String renderHtml(String source, JsonNode root) {
        String rendered = renderHtmlFragment(source, root, root);
        String unresolvedCheck = rendered.replace("{{@echarts}}", "");
        if (unresolvedCheck.contains("{{")) {
            throw new IllegalArgumentException("HTML 模板存在未解析变量或未闭合区块");
        }
        return rendered;
    }

    private String renderHtmlFragment(String source, JsonNode context, JsonNode root) {
        String current = source;
        for (int pass = 0; pass < 20; pass++) {
            Matcher section = SECTION.matcher(current);
            if (!section.find()) break;
            StringBuffer expanded = new StringBuffer();
            do {
                String name = section.group(1);
                String body = section.group(2);
                JsonNode value = resolveWithContext(context, root, name);
                String replacement = renderSection(body, value, root);
                section.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
            } while (section.find());
            section.appendTail(expanded);
            current = expanded.toString();
        }

        Matcher binding = BINDING.matcher(current);
        StringBuffer rendered = new StringBuffer();
        while (binding.find()) {
            String name = binding.group(1);
            JsonNode value = resolveWithContext(context, root, name);
            String replacement = value == null || value.isNull() || value.isMissingNode()
                    ? "-"
                    : escapeHtml(scalarText(value, name));
            binding.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        binding.appendTail(rendered);
        return rendered.toString();
    }

    private String renderSection(String body, JsonNode value, JsonNode root) {
        if (value == null || value.isNull() || value.isMissingNode() || !truthy(value)) return "";
        if (value.isArray()) {
            StringBuilder out = new StringBuilder();
            for (JsonNode item : value) out.append(renderHtmlFragment(body, item, root));
            return out.toString();
        }
        JsonNode context = value.isObject() ? value : root;
        return renderHtmlFragment(body, context, root);
    }

    private String assembleDocument(String html, String chartJson) {
        String chartBlock = chartJson == null ? "" : "\n```echarts\n" + chartJson + "\n```\n";
        if (!hasText(html)) {
            return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                    + "<body><h2>ECharts 图表</h2>" + chartBlock + "</body></html>";
        }

        String combined = html.contains("{{@echarts}}")
                ? html.replace("{{@echarts}}", chartBlock)
                : injectChart(html, chartBlock);
        if (!looksLikeDocument(combined)) {
            combined = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>"
                    + combined + "</body></html>";
        }
        return combined;
    }

    private String injectChart(String html, String chartBlock) {
        if (chartBlock.isEmpty()) return html;
        Matcher body = Pattern.compile("(?i)<body[^>]*>").matcher(html);
        if (!body.find()) return chartBlock + html;
        return html.substring(0, body.end()) + chartBlock + html.substring(body.end());
    }

    private void validateVariables(String schemaJson, JsonNode variables) {
        ArrayNode schema = parseSchema(schemaJson);
        if (schema.isEmpty()) return;
        SchemaDefinition definition = validateSchemaDefinition(schema);
        Map<String, String> types = definition.types();
        Set<String> required = definition.required();
        Iterator<String> names = variables.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!types.containsKey(name)) throw new IllegalArgumentException("未声明的模板变量: " + name);
            if (!matchesType(variables.get(name), types.get(name))) {
                throw new IllegalArgumentException("模板变量类型错误: " + name + " 应为 " + types.get(name));
            }
        }
        for (String name : required) {
            if (!variables.has(name) || variables.get(name).isNull()) throw new IllegalArgumentException("缺少必填模板变量: " + name);
        }
    }

    private SchemaDefinition validateSchemaDefinition(ArrayNode schema) {
        Map<String, String> types = new HashMap<>();
        Set<String> required = new HashSet<>();
        Set<String> supported = Set.of("string", "number", "integer", "boolean", "array", "object");
        for (JsonNode field : schema) {
            if (!field.isObject()) throw new IllegalArgumentException("variable_schema 每项必须是 JSON 对象");
            String name = requiredSchemaText(field, "name");
            String type = requiredSchemaText(field, "type").toLowerCase();
            if (!supported.contains(type)) throw new IllegalArgumentException("variable_schema 不支持类型: " + type);
            if (types.put(name, type) != null) throw new IllegalArgumentException("variable_schema 变量重复: " + name);
            if (field.path("required").asBoolean(false)) required.add(name);
        }
        return new SchemaDefinition(types, required);
    }

    private ArrayNode parseSchema(String schemaJson) {
        if (!hasText(schemaJson)) return MAPPER.createArrayNode();
        try {
            JsonNode schema = MAPPER.readTree(schemaJson);
            if (!schema.isArray()) throw new IllegalArgumentException("variable_schema 必须是 JSON 数组");
            return (ArrayNode) schema;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("variable_schema 不是合法 JSON: " + e.getMessage(), e);
        }
    }

    private static boolean matchesType(JsonNode value, String type) {
        if (value == null || value.isNull()) return true;
        return switch (type) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> throw new IllegalArgumentException("variable_schema 不支持类型: " + type);
        };
    }

    private static JsonNode resolveWithContext(JsonNode context, JsonNode root, String path) {
        JsonNode local = resolvePath(context, path);
        return local == null || local.isMissingNode() ? resolvePath(root, path) : local;
    }

    private static JsonNode resolvePath(JsonNode node, String path) {
        if (node == null) return null;
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            if (!current.isObject()) return null;
            current = current.get(part);
            if (current == null) return null;
        }
        return current;
    }

    private static JsonNode parseObject(String json, String label) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) throw new IllegalArgumentException(label + " 必须是 JSON 对象");
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " 不是合法 JSON: " + e.getMessage(), e);
        }
    }

    private static String requiredSchemaText(JsonNode field, String name) {
        JsonNode value = field.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("variable_schema 每项必须包含 " + name);
        }
        return value.asText();
    }

    private static String scalarText(JsonNode value, String name) {
        if (value.isContainerNode()) throw new IllegalArgumentException("HTML 标量位置不能绑定数组或对象: " + name);
        return value.asText();
    }

    private static boolean truthy(JsonNode value) {
        if (value.isArray() || value.isObject()) return !value.isEmpty();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isTextual()) return !value.asText().isBlank();
        if (value.isNumber()) return value.asDouble() != 0;
        return !value.isNull();
    }

    private static String textVariable(JsonNode variables, String name, String fallback) {
        JsonNode value = variables.get(name);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static boolean looksLikeDocument(String html) {
        String leading = html.stripLeading().toLowerCase();
        return leading.startsWith("<!doctype") || leading.startsWith("<html");
    }

    private static void checkLength(String value, String name) {
        if (value != null && value.length() > MAX_TEMPLATE_LENGTH) {
            throw new IllegalArgumentException(name + " 超过 1MB 限制");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Rendered(String title, String html) {}
    private record SchemaDefinition(Map<String, String> types, Set<String> required) {}
}
