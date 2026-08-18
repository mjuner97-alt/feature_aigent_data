package com.agentscopea2a.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** User-maintained ECharts/HTML presentation template stored in GaussDB. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresentationTemplateEntry {
    private Long id;
    private String templateId;
    private String name;
    private String description;
    /** ECharts option JSON template; nullable when this is an HTML-only template. */
    private String echartsTemplate;
    /** HTML document or fragment template; nullable when this is an ECharts-only template. */
    private String htmlTemplate;
    /** JSON array using the same name/type/required convention as other registries. */
    private String variableSchema;
    /** inline / sql. SQL templates execute the bound registered SQL on the server. */
    private String dataProviderType;
    /** Bound sql_registry.sql_id when dataProviderType=sql. */
    private String dataProviderId;
    /** Server-side adapter that converts provider rows into template variables. */
    private String dataAdapter;
    /** Optional JSON object mapping presentation parameter names to provider parameter names. */
    private String parameterMapping;
    /** 0=disabled, 1=enabled. */
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
}
