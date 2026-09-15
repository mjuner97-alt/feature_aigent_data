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
}

export interface SkillRoutingInput {
  shortSummary: string;
  keywords: string[];
  domainTags: string[];
  topicTags: string[];
  active: boolean;
}
