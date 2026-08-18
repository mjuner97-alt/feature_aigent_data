package com.agentscopea2a.v2.presentation;

import com.agentscopea2a.entity.PresentationTemplateEntry;
import com.agentscopea2a.mapper.gauss.PresentationTemplateMapper;
import com.agentscopea2a.v2.service.DownloadContentService;
import com.agentscopea2a.v2.skillManager.report.HtmlReportRenderer;
import com.agentscopea2a.v2.tools.SqlRegistryExecTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PresentationRenderServiceTest {
    private PresentationTemplateMapper templateMapper;
    private DownloadContentService contentService;
    private SqlRegistryExecTool sqlTool;
    private PresentationDataReferenceStore referenceStore;
    private PresentationRenderService service;

    @BeforeEach
    void setUp() {
        templateMapper = mock(PresentationTemplateMapper.class);
        contentService = mock(DownloadContentService.class);
        sqlTool = mock(SqlRegistryExecTool.class);
        referenceStore = new PresentationDataReferenceStore();
        HtmlReportRenderer htmlRenderer = new HtmlReportRenderer();
        htmlRenderer.init();
        service = new PresentationRenderService(
                templateMapper,
                new RegisteredPresentationTemplateRenderer(htmlRenderer),
                contentService,
                sqlTool,
                referenceStore,
                new PresentationDataAdapterRegistry(),
                "http://localhost:5174/",
                168);
        when(templateMapper.selectByTemplateId("q21/report-v1")).thenReturn(template());
        when(contentService.create(anyString(), anyString(), anyString(), any())).thenReturn("report123");
    }

    @Test
    void executesBoundSqlFromShortParamsAndReturnsSummary() {
        Map<String, Object> params = Map.of("dept", "杭州开发二部", "version", "2026年7月份版本");
        when(sqlTool.executeStructured("q2_1_report_by_dept_version", params))
                .thenReturn(queryResult(params));

        PresentationRenderService.Result result = service.render("q21/report-v1", params, null, null);

        assertEquals("report123", result.reportId());
        assertEquals("http://localhost:5174/api/presentation/reports/report123", result.url());
        assertEquals("100.00%", result.summary().path("passedPctText").asText());
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(contentService).create(html.capture(), anyString(), anyString(), any());
        assertTrue(html.getValue().contains("杭州开发二部"));
        assertTrue(html.getValue().contains("100.00%"));
    }

    @Test
    void consumesResultReferenceWithoutExecutingSqlAgain() {
        String ref = referenceStore.put("sql", "q2_1_report_by_dept_version", rows());

        PresentationRenderService.Result result = service.render("q21/report-v1", Map.of(), ref, null);

        assertEquals(80, result.summary().path("total").asInt());
        verify(sqlTool, never()).executeStructured(anyString(), any());
    }

    private static SqlRegistryExecTool.QueryResult queryResult(Map<String, Object> params) {
        return new SqlRegistryExecTool.QueryResult(
                "q2_1_report_by_dept_version", params,
                List.of("variables_json", "summary_json"), rows(), 12);
    }

    private static List<Map<String, Object>> rows() {
        return List.of(Map.of(
                "variables_json", "{"
                        + "\"title\":\"杭州开发二部 2026年7月份版本 Q2-1 指标报告\","
                        + "\"versions\":[\"2026年7月份版本\"],"
                        + "\"scoredRates\":[0.0],\"passedRates\":[100.0],"
                        + "\"records\":[{\"department\":\"杭州开发二部\",\"passedPctText\":\"100.00%\"}],"
                        + "\"dataSource\":\"GaussDB test\"}",
                "summary_json", "{\"department\":\"杭州开发二部\",\"version\":\"2026年7月份版本\","
                        + "\"total\":80,\"scored\":0,\"passed\":80,"
                        + "\"scoredPctText\":\"0.00%\",\"passedPctText\":\"100.00%\"}"));
    }

    private static PresentationTemplateEntry template() {
        return PresentationTemplateEntry.builder()
                .templateId("q21/report-v1")
                .name("Q2-1 报告")
                .echartsTemplate("{\"xAxis\":{\"data\":\"{{versions}}\"},\"series\":[{\"data\":\"{{passedRates}}\"}]}")
                .htmlTemplate("<table>{{#records}}<tr><td>{{department}}</td><td>{{passedPctText}}</td></tr>{{/records}}</table>")
                .variableSchema("["
                        + "{\"name\":\"title\",\"type\":\"string\",\"required\":true},"
                        + "{\"name\":\"versions\",\"type\":\"array\",\"required\":true},"
                        + "{\"name\":\"scoredRates\",\"type\":\"array\",\"required\":true},"
                        + "{\"name\":\"passedRates\",\"type\":\"array\",\"required\":true},"
                        + "{\"name\":\"records\",\"type\":\"array\",\"required\":true},"
                        + "{\"name\":\"dataSource\",\"type\":\"string\",\"required\":true}]")
                .dataProviderType("sql")
                .dataProviderId("q2_1_report_by_dept_version")
                .dataAdapter("json-envelope-v1")
                .parameterMapping("{}")
                .enabled(1)
                .build();
    }
}
