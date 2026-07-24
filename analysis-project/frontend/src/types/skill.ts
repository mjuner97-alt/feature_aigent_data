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
  rank: number | null;
  updatedAt: string;
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
  category: string;
  tags: string;
}
