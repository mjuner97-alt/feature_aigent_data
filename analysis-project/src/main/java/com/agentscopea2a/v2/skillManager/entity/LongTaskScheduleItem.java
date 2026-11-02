package com.agentscopea2a.v2.skillManager.entity;
import java.util.List;

/** 自动触发调度所需的流程、类型和标签组合。 */
public record LongTaskScheduleItem(SkillFlow flow, LongTaskType type, List<LongTaskTag> tags) {}
