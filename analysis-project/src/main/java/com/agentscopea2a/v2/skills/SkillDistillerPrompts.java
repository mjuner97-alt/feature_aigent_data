/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.skills;

/**
 * Prompt templates for {@link SkillDistiller}. Extracted from
 * {@code SkillDistiller} (P2-1) so prompt strings live in one place -
 * easier to A/B test wording without touching the model-calling flow.
 *
 * <p>All builders are static and return plain strings. The caller is responsible
 * for wrapping in a {@code Msg} and invoking the model.
 */
final class SkillDistillerPrompts {

    /** Prompt suffix appended on retry - asks LLM to re-emit only the structure. */
    static final String RETRY_PROMPT_SUFFIX =
            "\n\n-----\n以上输出格式不符合要求。请只输出以下四段(不要多余内容):\n"
                    + "1. `name: <英文小写下划线>`\n"
                    + "2. `description: <一句话中文描述>`\n"
                    + "3. `sample_questions:` 然后 3-5 行 `- <中文问法>`\n"
                    + "4. ```markdown 包裹的 SKILL.md 正文\n"
                    + "不要 YAML frontmatter。";

    private SkillDistillerPrompts() {
    }

    /**
     * Question-only distillation prompt (no tool-call context).
     *
     * @param question        the exemplar user question
     * @param fingerprintHint L1 fingerprint hint shown to the LLM
     * @param metricContext   pre-formatted metric-tag context (may be empty)
     * @param traceBlock      pre-formatted episodic trace block (may be empty)
     */
    static String distillPrompt(
            String question, String fingerprintHint, String metricContext, String traceBlock) {
        return "你是一位技术文档撰写者,正在为'质量数据智能助手'编写一份操作手册章节。下面是用户反复提出的同类问题:\n\n"
                + "**用户问题示例**: "
                + question
                + "\n**问题指纹**: "
                + fingerprintHint
                + "\n"
                + metricContext
                + "\n\n请撰写一份 markdown 文档,描述这类问题的标准处理流程。文档必须以纯文本形式输出,不要调用任何外部函数,不要使用 tool_calls。\n\n"
                + "输出格式为四段:\n"
                + "1. 第一行: `name: <英文小写下划线>` (例如 `quarterly_quality_compare`)\n"
                + "2. 第二行: `description: <一句话中文描述>`\n"
                + "3. 接着一段 `sample_questions:`,后面跟 3-5 行 `- <同维度的中文问法>`(同一类问题的不同措辞)\n"
                + "4. 最后用 ```markdown 包裹一段正文,描述完整的处理流程,必须包含以下章节:\n"
                + "   - 父智能体如何派单(指定 query_data / analyze_data 等子智能体名称)\n"
                + "   - 子智能体的处理步骤(查阅 tool_index 选择 toolId,调用 toolMetaInfo 获取参数定义,调用 router_tool 执行)\n"
                + "   - 每步的入参 JSON 示例与返回结果格式\n"
                + "   - 调用顺序图(用 ASCII 箭头 Supervisor -> query_data -> tool_index -> router_tool)\n"
                + "   - 关键约束与异常处理\n"
                + "工具名只能用真实名称(tool_index / toolMetaInfo / router_tool / agent_spawn),不要写 'query_quality_data' 或 'python_exec'。\n"
                + "正文长度至少 60 行,内容要详细完整,参考 .agentscope/workspace/harness-a2a/skills-auto/sample_range_analysis 的写法。\n"
                + "不要输出 YAML frontmatter,系统会自动生成。";
    }

    /**
     * Context-aware distillation prompt - includes a rich tool-call trace so the LLM
     * can capture not just <em>what</em> tools to use but <em>how</em> to call them
     * with the right arguments.
     */
    static String distillWithContextPrompt(
            String question,
            String fingerprintHint,
            String toolCallContext,
            String traceBlock,
            String metricContext) {
        return "你正在为一个'质量数据智能助手'蒸馏可复用的 skill。下面是一个用户多次出现的同类问题:\n\n"
                + "**用户问题**: " + question + "\n"
                + "**Fingerprint**: " + fingerprintHint + "\n"
                + metricContext
                + toolCallContext
                + traceBlock
                + "\n\n请输出四段:\n"
                + "1. 一行 `name: <英文小写下划线>` (例如 `quarterly_quality_compare`)\n"
                + "2. 一行 `description: <一句话中文描述>`\n"
                + "3. 一段 `sample_questions:`,紧跟 3-5 行 `- <同维度的中文问法>`(同一个 skill 的不同措辞,"
                + "应覆盖该 skill 适用的几种典型问法;不要复制 exemplar question 原句)\n"
                + "4. 一段 ```markdown 包裹的 SKILL.md 正文,描述本类问题的完整解决步骤:\n"
                + "   - 父智能体派单给哪个子智能体(如 query_data / analyze_data)\n"
                + "   - 子智能体内部的工具调用链路:先查阅哪个 skill/toolId -> 再调哪些元工具\n"
                + "   - 每个工具的入参与出参:输入了什么参数、返回了什么结果\n"
                + "参考上面的**工具调用链路详情**来编写,工具名、参数格式、调用顺序必须与实际一致。\n"
                + "不要使用'query_quality_data'或'python_exec'等泛化工具名,要用 toolMetaInfo/router_tool 等真实链路。\n"
                + "不要输出 YAML frontmatter,系统会自动生成。";
    }

    /**
     * PR4 evolve prompt - rewrites an existing SKILL.md body to avoid the recent failure
     * mode. Tightly constrained: name must equal the original, old body is given in full
     * so the LLM can surgically fix rather than rewrite, failed trace is truncated to
     * ~500 chars by the caller.
     */
    static String evolvePrompt(
            String skillName, String oldBody, String exemplarQuestion, String failedTrace) {
        String trace = failedTrace == null || failedTrace.isBlank()
                ? "(本次演进无具体失败 trace,LLM 凭判断修订)"
                : failedTrace.trim();
        return "你正在演进一个已经存在的 SKILL.md。下面是当前版本(它最近被多次使用但表现不佳,"
                + "需要修订):\n\n"
                + "**Skill name**: "
                + skillName
                + " (保持不变!如果想换名字,请直接返回原名)\n"
                + "**用户问题**: "
                + (exemplarQuestion == null ? "(无)" : exemplarQuestion)
                + "\n\n**本次失败的工具调用详情(≤500字)**:\n```\n"
                + trace
                + "\n```\n\n**当前 SKILL.md 正文**:\n```markdown\n"
                + oldBody
                + "\n```\n\n请基于以上失败信号,修订正文,使其能够避免这类失败。"
                + "注意失败详情中标注了每个工具调用的输入和输出--请分析失败原因并补充对应约束。"
                + "请按与蒸馏相同的格式输出四段:\n"
                + "1. 一行 `name: "
                + skillName
                + "` (必须与原 skill 名一致)\n"
                + "2. 一行 `description: <一句话中文描述,可微调>`\n"
                + "3. 一段 `sample_questions:`,紧跟 3-5 行 `- <同维度中文问法>`\n"
                + "4. 一段 ```markdown 包裹的修订后正文,**只改需要改的地方**,"
                + "在改动处用注释或文字说明本次修订点与失败原因的对应关系。\n"
                + "不要输出 YAML frontmatter,系统会自动生成。";
    }
}
