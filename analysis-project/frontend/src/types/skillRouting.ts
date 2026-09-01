export interface SkillRoutingMetadata {
  skillName: string;
  description: string | null;
  shortSummary: string;
  aliases: string[];
  keywords: string[];
  metricTags: string[];
  domainTags: string[];
  dataSourceTags: string[];
  priority: number;
  active: boolean;
  updatedAt: string | null;
  configured: boolean;
}

export interface SkillRoutingInput {
  shortSummary: string;
  aliases: string[];
  keywords: string[];
  metricTags: string[];
  domainTags: string[];
  dataSourceTags: string[];
  priority: number;
  active: boolean;
}
