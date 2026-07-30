package com.agentscopea2a.v2.skills;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于数据库的 Skill 仓库实现，从 skill_manage 表加载用户创建的 skill。
 *
 * <p>通过 {@link SkillMapper} 访问 {@code skill_manage} 表，按 {@code owner_user_id} 隔离，
 * 仅返回 ACTIVE 状态的 skill。userId 在构造时确定，整个实例服务于单个用户请求。
 */
public class DatabaseSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSkillRepository.class);

    private final SkillMapper skillMapper;
    private final String userId;
    private boolean writeable = true;

    /**
     * 构造函数。
     *
     * @param skillMapper MyBatis Mapper，操作 skill_manage 表
     * @param userId      当前请求的用户 ID（对应 skill_manage.owner_user_id）
     */
    public DatabaseSkillRepository(SkillMapper skillMapper, String userId) {
        this.skillMapper = skillMapper;
        this.userId = userId;
    }

    @Override
    public AgentSkill getSkill(String name) {
        try {
            if (userId == null) {
                return null;
            }
            Skill skill = skillMapper.selectByRetrievalNameAndOwner(name, userId);
            if (skill == null) {
                return null;
            }
            return AgentSkill.builder()
                    .name(skill.getRetrievalName())
                    .description(skill.getDescription())
                    .skillContent(skill.getContent())
                    .source("user_generated")
                    .build();
        } catch (Exception e) {
            log.warn("DatabaseSkillRepository: failed to get skill '{}': {}", name, e.getMessage());
            return null;
        }
    }

    @Override
    public List<String> getAllSkillNames() {
        try {
            if (userId == null) {
                return List.of();
            }
            List<String> names = skillMapper.selectActiveRetrievalNamesByUser(userId);
            if (names == null) {
                return List.of();
            }
            return names.stream().filter(n -> n != null).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("DatabaseSkillRepository: failed to get all skill names: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        try {
            if (userId == null) {
                return List.of();
            }
            List<Skill> skills = skillMapper.selectActiveByUser(userId);
            if (skills == null || skills.isEmpty()) {
                return List.of();
            }
            return skills.stream()
                    .map(this::toAgentSkill)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("DatabaseSkillRepository: failed to get all skills: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        if (userId == null) {
            return false;
        }
        if (skills == null || skills.isEmpty()) {
            return true;
        }

        boolean allSuccess = true;
        for (AgentSkill skill : skills) {
            try {
                if (skillExists(skill.getName())) {
                    if (!force) {
                        continue;
                    }
                    // Update existing skill
                    Skill update = Skill.builder()
                            .name(skill.getDescription() != null ? skill.getDescription() : skill.getName())
                            .description(skill.getDescription())
                            .content(skill.getSkillContent())
                            .retrievalName(skill.getName())
                            .ownerUserId(userId)
                            .status("ACTIVE")
                            .likeCount(0L)
                            .build();
                    int rows = skillMapper.updateByRetrievalNameAndOwner(update);
                    if (rows == 0) {
                        log.warn("DatabaseSkillRepository: failed to update skill '{}'", skill.getName());
                        allSuccess = false;
                    }
                } else {
                    // Insert new skill
                    Skill insert = Skill.builder()
                            .name(skill.getDescription() != null ? skill.getDescription() : skill.getName())
                            .description(skill.getDescription())
                            .content(skill.getSkillContent())
                            .retrievalName(skill.getName())
                            .ownerUserId(userId)
                            .status("ACTIVE")
                            .likeCount(0L)
                            .build();
                    skillMapper.insertSkill(insert);
                }
            } catch (Exception e) {
                log.warn("DatabaseSkillRepository: failed to save skill '{}': {}", skill.getName(), e.getMessage());
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    @Override
    public boolean delete(String skillName) {
        try {
            if (userId == null) {
                return false;
            }
            int rows = skillMapper.softDeleteByRetrievalNameAndOwner(skillName, userId);
            return rows > 0;
        } catch (Exception e) {
            log.warn("DatabaseSkillRepository: failed to delete skill '{}': {}", skillName, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean skillExists(String skillName) {
        try {
            if (userId == null) {
                return false;
            }
            return skillMapper.existsByRetrievalNameAndOwner(skillName, userId);
        } catch (Exception e) {
            log.warn("DatabaseSkillRepository: failed to check skill existence '{}': {}", skillName, e.getMessage());
            return false;
        }
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("database", "skill_manage", isWriteable());
    }

    @Override
    public String getSource() {
        return "database";
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    @Override
    public void close() {
        // No resources to release
    }

    /**
     * 将 Skill 实体转换为 AgentSkill 对象。
     */
    private AgentSkill toAgentSkill(Skill skill) {
        return AgentSkill.builder()
                .name(skill.getRetrievalName())
                .description(skill.getDescription())
                .skillContent(skill.getContent())
                .source("user_generated")
                .build();
    }
}