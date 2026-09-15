export interface RoutingOverlapItem {
  skillName: string;
  skillOwnerUserId: string;
  skillSummary: string;
  toolId: string;
  toolType: string;
  level: 'HIGH' | 'MEDIUM' | 'LOW';
  topicTagOverlap: string[];
  cosine: number;
  aliasHit: boolean;
  toolIdLiteralInDescription: boolean;
  suggestion: string;
}

export interface RoutingOverlapListResponse {
  degraded: boolean;
  total: number;
  items: RoutingOverlapItem[];
}

export interface RoutingOverlapSummary {
  degraded: boolean;
  counts: Record<string, number>;
  highBySkill: Record<string, number>;
  highByTool: Record<string, number>;
}

export interface SkillSimilarityMatch {
  skillId: number;
  name: string;
  description: string;
  ownerUserId: string;
  visibility: string;
  similarity: number;
  evidence: string[];
}

export interface SkillSimilarityCheckResult {
  degraded: boolean;
  matches: SkillSimilarityMatch[];
}
