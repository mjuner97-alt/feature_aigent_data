package com.agentscopea2a.v2.skillManager.entity;
import java.util.List;
public record LongTaskScheduleItem(SkillFlow flow, LongTaskType type, List<LongTaskTag> tags) {}
