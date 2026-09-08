export interface SkillRoutingMetadata {
  skillName: string;
  description: string | null;
  shortSummary: string;
  keywords: string[];
  domainTags: string[];
  topicTags: string[];
  metricTags: string[];
  creator: string;
  priority: number;
  active: boolean;
  updatedAt: string | null;
  configured: boolean;
}

export interface SkillRoutingInput {
  shortSummary: string;
  keywords: string[];
  domainTags: string[];
  topicTags: string[];
  metricTags: string[];
  priority: number;
  active: boolean;
}
