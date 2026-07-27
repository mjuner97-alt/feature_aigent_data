export interface SkillListItem {
  id: number;
  name: string;
  description: string;
  category: string;
  tags: string;
  ownerUserId: string;
  likeCount: number;
  liked: boolean;
  used: boolean;
  available: boolean;
  disabled: boolean;
  rank: number | null;
  updatedAt: string;
  dimension: string;
}

export interface SkillDetail {
  id: number;
  name: string;
  description: string;
  content: string;
  category: string;
  tags: string;
  ownerUserId: string;
  status: string;
  likeCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface LikeStatus {
  liked: boolean;
  likeCount: number;
}

/** 创建/编辑 Skill 的请求体。对应后端 POST /api/skills 与 PUT /api/skills?id=。 */
export interface SkillInput {
  name: string;
  description: string;
  content: string;
  category?: string;   // 后端保留字段,前端不再使用,可选
  tags?: string;       // 后端保留字段,前端不再使用,可选
}

/** Skill 发布记录。对应 GET /api/skills/{id}/publishes 的单条(对齐后端 SkillPublish 实体)。 */
export interface SkillPublishRecord {
  id: number;
  skillId: number;
  targetType: string;   // GROUP / DEPARTMENT / PRODUCT_LINE / COMPANY
  targetId: string;
  targetName: string;   // 组织显示名称(如"开发一组"、"杭研")
  status: string;       // PENDING / APPROVED / REJECTED
  submitter: string;
  approver: string | null;
  approveTime: string | null;
  currentApproverUserId: string | null;
  lastApprovalComment: string | null;
  lastApprovalAt: string | null;
  createdAt: string;
}

/** 发布目标选项(单条)。对应后端 OrgTarget record。 */
export interface PublishTarget {
  orgType: string;
  orgId: string;
  displayName: string;   // 组织名称(如"开发一组")
  fullLabel: string;     // 完整维度标签(如"小组:开发一组"、"杭研")
}

/** 发布目标分组。对应后端 PublishTargetGroup record。 */
export interface PublishTargetGroup {
  orgType: string;
  typeLabel: string;     // 维度类型前缀(如"小组"、"部门")
  targets: PublishTarget[];
}
