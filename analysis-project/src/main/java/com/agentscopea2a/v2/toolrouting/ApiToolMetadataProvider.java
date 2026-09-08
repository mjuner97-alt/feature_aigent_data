package com.agentscopea2a.v2.toolrouting;

import java.util.Optional;

/** Read-only view of API tools registered by the reflection router. */
public interface ApiToolMetadataProvider {

    boolean isActiveApiTool(String toolId);

    Optional<ApiToolMetadata> findApiTool(String toolId);
}
