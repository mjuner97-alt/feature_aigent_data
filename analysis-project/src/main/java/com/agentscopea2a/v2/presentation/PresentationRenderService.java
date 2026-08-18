package com.agentscopea2a.v2.presentation;

import com.agentscopea2a.entity.PresentationTemplateEntry;
import com.agentscopea2a.mapper.gauss.PresentationTemplateMapper;
import com.agentscopea2a.v2.service.DownloadContentService;
import com.agentscopea2a.v2.tools.SqlRegistryExecTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PresentationRenderService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PresentationTemplateMapper templateMapper;
    private final RegisteredPresentationTemplateRenderer templateRenderer;
    private final DownloadContentService contentService;
    private final SqlRegistryExecTool sqlRegistryExecTool;
    private final PresentationDataReferenceStore dataReferenceStore;
    private final PresentationDataAdapterRegistry adapterRegistry;
    private final String baseUrl;
    private final long ttlHours;

    public PresentationRenderService(PresentationTemplateMapper templateMapper,
                                     RegisteredPresentationTemplateRenderer templateRenderer,
                                     DownloadContentService contentService,
                                     SqlRegistryExecTool sqlRegistryExecTool,
                                     PresentationDataReferenceStore dataReferenceStore,
                                     PresentationDataAdapterRegistry adapterRegistry,
                                     @Value("${harness.a2a.presentation.base-url:${harness.a2a.csv-download.base-url:}}") String baseUrl,
                                     @Value("${harness.a2a.presentation.ttl-hours:168}") long ttlHours) {
        this.templateMapper = templateMapper;
        this.templateRenderer = templateRenderer;
        this.contentService = contentService;
        this.sqlRegistryExecTool = sqlRegistryExecTool;
        this.dataReferenceStore = dataReferenceStore;
        this.adapterRegistry = adapterRegistry;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.ttlHours = Math.max(1, Math.min(ttlHours, 24 * 30));
    }

    public Result render(String templateId, String variablesJson) {
        return render(templateId, Collections.emptyMap(), null, variablesJson);
    }

    public Result render(String templateId, Map<String, Object> params,
                         String resultRef, String variablesJson) {
        if (templateId == null || templateId.isBlank()) throw new IllegalArgumentException("templateId 必填");
        PresentationTemplateEntry template;
        try {
            template = templateMapper.selectByTemplateId(templateId.trim());
        } catch (Exception e) {
            throw new IllegalStateException("查询 presentation_template_registry 失败: " + e.getMessage(), e);
        }
        if (template == null) throw new IllegalArgumentException("未知或已禁用的展示模板: " + templateId);

        ResolvedData resolved = resolveData(template, params, resultRef, variablesJson);
        RegisteredPresentationTemplateRenderer.Rendered report = templateRenderer.render(
                template, resolved.variables().toString());
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(ttlHours);
        String shortCode = contentService.create(report.html(), "presentation-" + safeFilePart(templateId) + ".html", "text/html", expiresAt);
        String url = baseUrl + "/api/presentation/reports/" + shortCode;
        return new Result(shortCode, report.title(), url, expiresAt, resolved.summary());
    }

    private ResolvedData resolveData(PresentationTemplateEntry template, Map<String, Object> params,
                                     String resultRef, String variablesJson) {
        boolean inline = variablesJson != null && !variablesJson.isBlank();
        boolean referenced = resultRef != null && !resultRef.isBlank();
        if (inline && referenced) throw new IllegalArgumentException("variables 与 resultRef 不能同时传入");
        if (inline) {
            try {
                JsonNode variables = MAPPER.readTree(variablesJson);
                if (!variables.isObject()) throw new IllegalArgumentException("variables 必须是 JSON 对象");
                return new ResolvedData(variables, variables.path("summary"));
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("variables 不是合法 JSON: " + e.getMessage(), e);
            }
        }

        Map<String, Object> safeParams = params == null ? Collections.emptyMap() : new LinkedHashMap<>(params);
        String providerType = normalized(template.getDataProviderType(), "inline");
        String providerId = template.getDataProviderId();
        java.util.List<Map<String, Object>> rows;
        Map<String, Object> adapterParams = safeParams;
        if (referenced) {
            PresentationDataReferenceStore.DataSet data = dataReferenceStore.get(resultRef);
            if (!providerType.equals(data.providerType()) || providerId == null
                    || !providerId.equals(data.providerId())) {
                throw new IllegalArgumentException("resultRef 的数据提供者与模板绑定不一致");
            }
            rows = data.rows();
        } else if ("sql".equals(providerType)) {
            Map<String, Object> providerParams = mapParams(template.getParameterMapping(), safeParams);
            SqlRegistryExecTool.QueryResult query = sqlRegistryExecTool.executeStructured(providerId, providerParams);
            rows = query.rows();
            adapterParams = providerParams;
        } else {
            throw new IllegalArgumentException("该模板需要 variables；只有绑定 sql 数据提供者的模板可直接传 params");
        }

        PresentationDataAdapterRegistry.AdaptedData adapted = adapterRegistry.adapt(
                template.getDataAdapter(), rows, adapterParams, template.getName());
        return new ResolvedData(adapted.variables(), adapted.summary());
    }

    private static Map<String, Object> mapParams(String mappingJson, Map<String, Object> params) {
        if (mappingJson == null || mappingJson.isBlank() || "{}".equals(mappingJson.trim())) return params;
        try {
            JsonNode mapping = MAPPER.readTree(mappingJson);
            if (!mapping.isObject()) throw new IllegalArgumentException("parameter_mapping 必须是 JSON 对象");
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (Map.Entry<String, Object> param : params.entrySet()) {
                JsonNode target = mapping.get(param.getKey());
                if (target == null || !target.isTextual()) {
                    throw new IllegalArgumentException("未声明的展示参数: " + param.getKey());
                }
                mapped.put(target.asText(), param.getValue());
            }
            return mapped;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("parameter_mapping 不是合法 JSON: " + e.getMessage(), e);
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase();
    }

    private static String safeFilePart(String value) { return value.replaceAll("[^a-zA-Z0-9_-]", "_"); }
    private record ResolvedData(JsonNode variables, JsonNode summary) {}
    public record Result(String reportId, String title, String url, LocalDateTime expiresAt,
                         JsonNode summary) {}
}
