export interface SkillListItem {
  id: number;
  name: string;
  description: string;
  category: string;
  tags: string;
  ownerUserId: string;
  ownerName?: string;
  visibility: string;       // PUBLIC(公开) / PRIVATE(私有) / PERSONAL(个人,默认)
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
  visibility?: string;      // PUBLIC(公开) / PRIVATE(私有) / PERSONAL(个人;缺失视为 PERSONAL)
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
  visibility?: string; // 可选:PERSONAL(默认,仅创建者) / PUBLIC(公开,需发布审批) / PRIVATE(私有)
}

/** Skill 私有可见性授权项。对应后端 GET /api/skills/{id}/grants 的单条。 */
export interface SkillGrant {
  grantType: string;    // USER / DEPARTMENT / GROUP / VIRTUAL_GROUP
  targetId: string;     // USER=统一认证号 / DEPARTMENT=部门名 / GROUP=统计组名 / VIRTUAL_GROUP=虚拟组名
  displayName: string;  // 展示名(USER 为姓名,其余为组织/组名)
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

/**
 * 待我审批的发布记录项。对应后端 GET /api/publish/pending 返回的 SkillPublish 实体。
 * 字段与 SkillPublishRecord 基本一致,但审批列表场景下 createdAt 即提交时间。
 */
export interface PublishPendingItem {
  id: number;
  skillId: number;
  targetType: string;    // GROUP / DEPARTMENT / PRODUCT_LINE / COMPANY
  targetId: string;
  targetName: string;    // 组织显示名称(如"开发一组"、"杭研")
  status: string;        // PENDING / APPROVED / REJECTED
  submitter: string;
  approver: string | null;
  approveTime: string | null;
  currentApproverUserId: string | null;
  lastApprovalComment: string | null;
  lastApprovalAt: string | null;
  createdAt: string;
  // 审批列表展示用冗余字段(后端可能附带,缺失时前端用 targetName 兜底)
  name?: string;
  description?: string;
  category?: string;
  createdBy?: string;
}

/** Skill 变更草稿。对应后端 SkillDraft 实体,用于草稿审批流。 */
export interface SkillDraft {
  id: number;
  skillId: number;
  name: string;
  description: string;
  content: string;
  category: string;
  tags: string;
  status: string;        // PENDING / APPROVED / REJECTED
  submitter: string;
  approver: string | null;
  approveComment: string | null;
  submittedAt: string;
  approvedAt: string | null;
  // 审批列表展示用冗余字段
  createdBy?: string;
  createdAt?: string;
}

/** Skill 文件上传响应 */
export interface SkillFileUploadResponse {
  id: number;
  filename: string;
  fileType: string;
  fileSize: number;
  description: string | null;
  createdAt: string;
}

/** Skill 文件列表项 */
export interface SkillFileItem {
  id: number;
  filename: string;
  fileType: string;
  fileSize: number;
  description: string | null;
  createdAt: string;
  updatedAt: string | null;
}

/** Skill 附件引用项(含引用信息) */
export interface SkillFileReferenceItem {
  id: number;
  filename: string;
  fileType: string;
  fileSize: number;
  description: string | null;
  referenceType: string;
  referencedAt: string;
}

/** Skill 文件引用请求 */
export interface SkillFileReferenceRequest {
  fileId: number;
  referenceType?: string;
}
