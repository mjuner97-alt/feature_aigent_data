export interface SkillRoutingMetadata {
  skillName: string;
  description: string | null;
  shortSummary: string;
  keywords: string[];
  domainTags: string[];
  topicTags: string[];
  creator: string;
  active: boolean;
  updatedAt: string | null;
  configured: boolean;
  level1Keywords: string[];
  level2Keywords: string[];
  level3Keywords: string[];
}

export interface SkillRoutingInput {
  shortSummary: string;
  keywords: string[];
  domainTags: string[];
  topicTags: string[];
  active: boolean;
  level1Keywords: string[];
  level2Keywords: string[];
  level3Keywords: string[];
}
