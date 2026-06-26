/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.harness.tools;

import com.agentscopea2a.agent.tools.AgentTools;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRoutersIndex {

    private static final Logger log = LoggerFactory.getLogger(ToolRoutersIndex.class);

    /** JSON 中用来标识子工具的字段名。 */
    public static final String TOOL_ID_FIELD = "toolId";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** toolId -> 该方法所在的 Bean 实例。 */
    private final Map<String, Object> toolInstanceMap = new ConcurrentHashMap<>();

    /** toolId -> 方法及其参数描述。 */
    private final Map<String, MethodInfo> toolMethodMap = new ConcurrentHashMap<>();

    private final AgentTools agentTools;
    private final DataPrimitivesTool dataPrimitivesTool;

    @Autowired
    public ToolRoutersIndex(AgentTools agentTools, DataPrimitivesTool dataPrimitivesTool) {
        this.agentTools = agentTools;
        this.dataPrimitivesTool = dataPrimitivesTool;
    }

    /** 容器装配完成后,反射扫描各 Tool Bean,登记 @Tool 方法。 */
    @PostConstruct
    public void init() {
        registerTools(AgentTools.class, agentTools);
        registerTools(DataPrimitivesTool.class, dataPrimitivesTool);
        log.info("ToolRoutersIndex 初始化完成,已注册工具: {}", toolMethodMap.keySet());
    }

    public Map<String, MethodInfo> getToolMethodMap() {
        return Collections.unmodifiableMap(toolMethodMap);
    }

    @Tool(
            name = "router_tool",
            description = "统一工具路由入口,根据 JSON 中的 toolId 调用对应子工具。"
                    + "调用前可先用 toolMetaInfo 查询可用 toolId、描述和参数。"
                    + "示例: {\"toolId\":\"agent_tools_ping\",\"echo\":\"hi\"}")
    public Object router_tool(
            @ToolParam(
                            name = "paramsJson",
                            description =
                                    "JSON 格式参数,必须包含 toolId 字段,其余字段按目标子工具参数传入。示例: "
                                            + "{\"toolId\":\"agent_tools_ping\",\"echo\":\"hi\"}")
                    String paramsJson) {
        String toolId = null;
        try {
            Map<String, Object> params = parseJsonParams(paramsJson);
            toolId = extractToolId(params);
            if (toolId == null) {
                return "错误: JSON 中缺少 toolId 字段";
            }
            log.info("router_tool called: toolId={}, paramsJson={}", toolId, paramsJson);
            long startNanos = System.nanoTime();
            Object result = executeTool(toolId, params);
            log.info(
                    "router_tool finished: toolId={}, elapsedMs={}",
                    toolId,
                    (System.nanoTime() - startNanos) / 1_000_000);
            return result;
        } catch (JsonProcessingException e) {
            log.error("工具执行失败(JSON 解析): toolId={}, paramsJson={}", toolId, paramsJson, e);
            throw new IllegalArgumentException("工具执行失败: " + e.getMessage(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            log.error("工具执行失败: toolId={}, paramsJson={}", toolId, paramsJson, cause);
            throw new IllegalStateException("工具执行失败: " + cause.getMessage(), cause);
        } catch (Exception e) {
            log.error("工具执行失败: toolId={}, paramsJson={}", toolId, paramsJson, e);
            throw new IllegalArgumentException("工具执行失败: " + e.getMessage(), e);
        }
    }

    @Tool(
            name = "toolMetaInfo",
            description = "按 toolId 查询 router_tool 可调用的子工具元信息,包括工具描述、参数名、参数类型和是否必填。toolId 必填。")
    public Object toolMetaInfo(
            @ToolParam(
                            name = "toolId",
                            description = "必填。先通过工具索引技能确定目标工具 ID,再传入这里查询该子工具元信息。")
                    String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return Map.of("error", "toolId 必填。请先加载工具索引技能确定目标工具 ID,再调用 toolMetaInfo(toolId)。");
        }
        String normalizedToolId = toolId.trim();
        log.info("toolMetaInfo called: toolId={}", normalizedToolId);
        MethodInfo info = toolMethodMap.get(normalizedToolId);
        if (info == null) {
            return Map.of("error", "未知的 toolId='" + normalizedToolId + "'。请检查工具索引技能中的工具 ID 是否正确。");
        }
        return toToolMeta(normalizedToolId, info);
    }

    // ==================== 注册阶段 ====================

    private void registerTools(Class<?> toolClass, Object instance) {
        for (Method method : toolClass.getDeclaredMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) {
                continue;
            }
            String toolId = getToolId(toolAnnotation, method);
            List<ParamInfo> paramInfos = extractParamInfos(method);
            toolInstanceMap.put(toolId, instance);
            toolMethodMap.put(toolId, new MethodInfo(method, paramInfos));
            log.debug("注册工具: {}, 来自类: {}", toolId, toolClass.getSimpleName());
        }
    }

    /** {@code @Tool(name="...")} 为空时回落到方法名。 */
    private String getToolId(Tool toolAnnotation, Method method) {
        return toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
    }

    private List<ParamInfo> extractParamInfos(Method method) {
        List<ParamInfo> paramInfos = new ArrayList<>();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        Type[] genericParamTypes = method.getGenericParameterTypes();
        Class<?>[] paramTypes = method.getParameterTypes();

        for (int i = 0; i < paramAnnotations.length; i++) {
            if (isAutoInjectedType(genericParamTypes[i])) {
                paramInfos.add(ParamInfo.autoInjected(genericParamTypes[i]));
                continue;
            }
            String name = null;
            boolean required = true;
            for (Annotation a : paramAnnotations[i]) {
                if (a instanceof ToolParam tp) {
                    name = tp.name();
                    required = tp.required();
                    break;
                }
            }
            // 没标 @ToolParam 也允许,默认拿参数声明序号当 fallback 名(供路由排错用)
            if (name == null || name.isEmpty()) {
                name = "arg" + i;
            }
            paramInfos.add(new ParamInfo(name, genericParamTypes[i], paramTypes[i], required));
        }
        return paramInfos;
    }

    /** 预留扩展:某些参数类型由路由层自动注入,而不是从 JSON 里取(如 RuntimeContext 等)。 */
    private boolean isAutoInjectedType(Type type) {
        // 暂无自动注入类型;后续如需可在此加判断,例如 type.equals(SomeContext.class)
        return false;
    }

    // ==================== 调用阶段 ====================

    private Object executeTool(String toolId, Map<String, Object> params)
            throws InvocationTargetException, IllegalAccessException {
        MethodInfo info = toolMethodMap.get(toolId);
        Object instance = toolInstanceMap.get(toolId);
        if (info == null || instance == null) {
            return "错误: 未知的 toolId='" + toolId + "',已注册: " + toolMethodMap.keySet();
        }

        Object[] args = new Object[info.paramInfos.size()];
        for (int i = 0; i < args.length; i++) {
            ParamInfo p = info.paramInfos.get(i);
            if (p.autoInjected) {
                args[i] = null; // 留给后续真正的自动注入逻辑
                continue;
            }
            Object raw = params.get(p.name);
            if (raw == null) {
                if (p.required) {
                    throw new IllegalArgumentException(
                            "缺少必填参数: " + p.name + " (toolId=" + toolId + ")");
                }
                args[i] = defaultForPrimitive(p.rawType);
            } else {
                // 用 Jackson 把 LinkedHashMap / List / 字符串等强转到目标类型
                args[i] = MAPPER.convertValue(raw, MAPPER.constructType(p.genericType));
            }
        }
        return info.method.invoke(instance, args);
    }

    private Map<String, Object> parseJsonParams(String json) throws JsonProcessingException {
        String processedJson = cleanJsonPrefix(json);
        if (processedJson == null || processedJson.isBlank()) {
            return Map.of();
        }
        return MAPPER.readValue(processedJson, new TypeReference<Map<String, Object>>() {});
    }

    /** 去掉 ```json … ``` 之类的 LLM 包装,容忍模型轻微的格式偏差。 */
    private String cleanJsonPrefix(String json) {
        if (json == null) {
            return null;
        }
        String s = json.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            } else {
                s = s.substring(3);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }
        return s;
    }

    private String extractToolId(Map<String, Object> params) {
        Object toolIdObj = params.get(TOOL_ID_FIELD);
        return toolIdObj instanceof String ? (String) toolIdObj : null;
    }

    private static Object defaultForPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }

    private Map<String, Object> toToolMeta(String toolId, MethodInfo info) {
        Tool tool = info.method.getAnnotation(Tool.class);
        List<Map<String, Object>> params = new ArrayList<>();
        for (ParamInfo param : info.paramInfos) {
            if (param.autoInjected || param.name == null) {
                continue;
            }
            params.add(
                    Map.of(
                            "name", param.name,
                            "type", param.genericType.getTypeName(),
                            "required", param.required));
        }
        return Map.of(
                "toolId", toolId,
                "description", tool != null ? tool.description() : "",
                "params", params,
                "routerExample", buildRouterExample(toolId, info.paramInfos));
    }

    private Map<String, Object> buildRouterExample(String toolId, List<ParamInfo> params) {
        Map<String, Object> example = new java.util.LinkedHashMap<>();
        example.put(TOOL_ID_FIELD, toolId);
        for (ParamInfo param : params) {
            if (!param.autoInjected && param.required && param.name != null) {
                example.put(param.name, exampleValue(param.rawType));
            }
        }
        return example;
    }

    private Object exampleValue(Class<?> type) {
        if (type == String.class) return "示例值";
        if (type == boolean.class || type == Boolean.class) return true;
        if (Number.class.isAssignableFrom(type)
                || type == byte.class
                || type == short.class
                || type == int.class
                || type == long.class
                || type == float.class
                || type == double.class) {
            return 1;
        }
        return Map.of();
    }

    // ==================== 反射元数据 ====================

    public static class MethodInfo {
        public final Method method;
        public final List<ParamInfo> paramInfos;

        public MethodInfo(Method method, List<ParamInfo> paramInfos) {
            this.method = method;
            this.paramInfos = paramInfos;
        }
    }

    public static class ParamInfo {
        public final String name;
        public final Type genericType;
        public final Class<?> rawType;
        public final boolean required;
        public final boolean autoInjected;

        public ParamInfo(String name, Type genericType, Class<?> rawType, boolean required) {
            this.name = name;
            this.genericType = genericType;
            this.rawType = rawType;
            this.required = required;
            this.autoInjected = false;
        }

        private ParamInfo(Type genericType) {
            this.name = null;
            this.genericType = genericType;
            this.rawType = (genericType instanceof Class<?>) ? (Class<?>) genericType : Object.class;
            this.required = false;
            this.autoInjected = true;
        }

        public static ParamInfo autoInjected(Type genericType) {
            return new ParamInfo(genericType);
        }
    }
}
