package com.agentscopea2a.v2.skills;

import java.util.List;

/** Result of selecting model-visible Skill candidates for one user question. */
public record SkillCandidateSelection(
        List<String> skillNames,
        boolean explicitNameMatched,
        boolean confident,
        boolean fallbackExpanded,
        List<String> matchedKeywords,
        boolean topFromLevelTrack,
        /** 截断前完整层级轨命中集 (比较器序, docs/202611/多skill命中全部直接执行方案.md §5①):
         *  供多 skill 直接执行绕开 maxVisibleSkills/fallbackVisibleSkills 截断取全集;
         *  firstMentionPos = 该 skill 全部真命中词在归一化问题中的最早出现位置
         *  (保底注入词无位置, 全靠注入 = Integer.MAX_VALUE 排最后)。 */
        List<LevelTrackHit> levelTrackHits,
        /** G4 门控源 (拍板⑥): 排序比较进行到 globalRank 位次决胜 —— 存在同深度并列的
         *  层级轨候选且该深度有命中词配置了 sort_order (优先级真正在分胜负) → 整轮降级旧 top-1。 */
        boolean priorityDecisive,
        /** G6 门控源: 候选池 (过滤后) 是否全部来自层级轨 —— 存在显式点名/存量轨候选时多执行不触发。 */
        boolean onlyLevelTrack) {

    /** 截断前单个层级轨命中候选 (多 skill 直接执行方案 §5①)。 */
    public record LevelTrackHit(String skillName, int depth, boolean partial,
                                List<String> matchedTerms, int firstMentionPos) {
        public LevelTrackHit {
            matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
        }
    }

    public SkillCandidateSelection {
        skillNames = skillNames == null ? List.of() : List.copyOf(skillNames);
        matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
        levelTrackHits = levelTrackHits == null ? List.of() : List.copyOf(levelTrackHits);
    }

    /** Legacy 4-arg shape (no matched keywords / track flag) for existing call sites. */
    public SkillCandidateSelection(List<String> skillNames, boolean explicitNameMatched,
                                   boolean confident, boolean fallbackExpanded) {
        this(skillNames, explicitNameMatched, confident, fallbackExpanded, List.of(), false);
    }

    /** Legacy 5-arg shape (no track flag) for existing call sites. */
    public SkillCandidateSelection(List<String> skillNames, boolean explicitNameMatched,
                                   boolean confident, boolean fallbackExpanded,
                                   List<String> matchedKeywords) {
        this(skillNames, explicitNameMatched, confident, fallbackExpanded, matchedKeywords, false);
    }

    /** Legacy 6-arg shape (no multi-exec fields) for existing call sites. */
    public SkillCandidateSelection(List<String> skillNames, boolean explicitNameMatched,
                                   boolean confident, boolean fallbackExpanded,
                                   List<String> matchedKeywords, boolean topFromLevelTrack) {
        this(skillNames, explicitNameMatched, confident, fallbackExpanded, matchedKeywords,
                topFromLevelTrack, List.of(), false, false);
    }
}
