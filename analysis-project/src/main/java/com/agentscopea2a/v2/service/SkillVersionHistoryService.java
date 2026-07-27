package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.entity.SkillVersionHistory;
import com.agentscopea2a.mapper.mysql.SkillVersionHistoryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 版本历史 Service。编辑 Skill 时存旧版本快照,支撑回溯与审计。
 */
@Service
public class SkillVersionHistoryService {

    private final SkillVersionHistoryMapper vhMapper;

    public SkillVersionHistoryService(SkillVersionHistoryMapper vhMapper) {
        this.vhMapper = vhMapper;
    }

    public void saveVersion(Skill skill, String editedBy, String editReason) {
        Integer maxVersion = vhMapper.selectMaxVersion(skill.getId());
        int nextVersion = maxVersion == null ? 1 : maxVersion + 1;
        vhMapper.insert(SkillVersionHistory.builder()
                .skillId(skill.getId())
                .version(nextVersion)
                .name(skill.getName())
                .description(skill.getDescription())
                .content(skill.getContent())
                .category(skill.getCategory())
                .tags(skill.getTags())
                .editedBy(editedBy)
                .editReason(editReason)
                .createdAt(LocalDateTime.now())
                .build());
    }

    public List<SkillVersionHistory> selectBySkillId(Long skillId) {
        return vhMapper.selectBySkillId(skillId);
    }
}
