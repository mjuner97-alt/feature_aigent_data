export type ToolType = 'SQL' | 'API' | 'SCRIPT';
export type TagType = 'DOMAIN' | 'TOPIC' | 'METRIC' | 'DIMENSION';

export interface ToolRoutingMetadata {
  toolId: string;
  toolType: ToolType;
  description: string;
  creator: string;
  topicTags: string[];
  metricTags: string[];
  dimensionTags: string[];
  priority: number;
  enabled: boolean;
}

export interface ToolRoutingInput {
  toolType: ToolType;
  description: string;
  topicTags: string[];
  metricTags: string[];
  dimensionTags: string[];
  priority: number;
  enabled: boolean;
}

export interface ToolRoutingScanCandidate {
  toolId: string;
  toolType: ToolType;
  name: string;
  description: string;
  sourceAvailable: boolean;
  configured: boolean;
  routeEnabled: boolean;
  issueCodes: string[];
}

export interface ToolRoutingTag {
  tagType: TagType;
  tagName: string;
  description: string;
  enabled: boolean;
}

export interface ToolRoutingStatus {
  globallyEnabled: boolean;
  configuredTools: number;
  enabledRoutes: number;
}
