package com.agentscopea2a.v2.skills;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import com.agentscopea2a.v2.skillManager.dto.SkillFileReferenceItem;
import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.entity.SkillFile;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final SkillUsageResolver usageResolver;
    private final String userId;
    /** skill 文件磁盘根目录(${skill.file.script}),与 SkillFileService 一致。
     *  DB 中 storage_path 存的是相对路径 {userId}/{filename},读盘时须拼本字段解析成绝对路径。 */
    private final String baseDir;
    private boolean writeable = true;

    /**
     * 构造函数。
     *
     * @param skillMapper MyBatis Mapper，操作 skill_manage 表
     * @param userId      当前请求的用户 ID（对应 skill_manage.owner_user_id）
     * @param baseDir     skill 文件磁盘根目录(${skill.file.script})，与 SkillFileService 一致；
     *                    用于把 DB 中的相对 storage_path 解析成绝对路径
     */
    public DatabaseSkillRepository(SkillMapper skillMapper, SkillUsageResolver usageResolver,
                                   String userId, String baseDir) {
        this.skillMapper = skillMapper;
        this.usageResolver = usageResolver;
        this.userId = userId;
        this.baseDir = baseDir;
    }

    @Override
    public AgentSkill getSkill(String name) {
        try {
            if (userId == null) {
                return null;
            }
            if (!usageResolver.findUsableRetrievalNames(userId).contains(name)) {
                return null;
            }
            // 优先按 owner 查(快速路径),未命中再按可访问范围查(含维度发布)
            Skill skill = skillMapper.selectByRetrievalNameAndOwner(name, userId);
            if (skill == null) {
                skill = skillMapper.selectByRetrievalNameAccessibleByUser(name, userId);
            }
            if (skill == null) {
                return null;
            }
            return AgentSkill.builder()
                    .name(skill.getRetrievalName())
                    .description(skill.getDescription())
                    .skillContent(skill.getContent())
                    .source("user_generated")
                    .resources(loadFileResources(skill.getId()))
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
            return new ArrayList<>(usageResolver.findUsableRetrievalNames(userId));
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
            var usableNames = usageResolver.findUsableRetrievalNames(userId);
            return skills.stream()
                    .filter(skill -> usableNames.contains(skill.getRetrievalName()))
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
     * 将 Skill 实体转换为 AgentSkill 对象(含附件文件资源)。
     */
    private AgentSkill toAgentSkill(Skill skill) {
        return AgentSkill.builder()
                .name(skill.getRetrievalName())
                .description(skill.getDescription())
                .skillContent(skill.getContent())
                .source("user_generated")
                .resources(loadFileResources(skill.getId()))
                .build();
    }

    /**
     * 加载 Skill 引用的附件文件,装入 AgentSkill.resources,使 LLM 可通过
     * {@code load_skill_through_path(name=<skillId>, path=<绝对路径>)} 读取脚本内容。
     *
     * <p>从 skill_file_reference 取引用列表,再按 (userId, filename) 查 skill_file 拿
     * storage_path,拼 {@code baseDir} 解析成绝对路径后读文本内容。resources 的 key 即该绝对路径
     * (正斜杠归一化)--SkillLoadTool.loadOne 按 key 精确匹配,故 LLM 须用同一绝对路径加载。
     * .py / .sql 均为文本;读失败跳过该文件不影响 skill 加载。
     */
    private Map<String, String> loadFileResources(Long skillId) {
        if (skillId == null || userId == null) {
            return Map.of();
        }
        try {
            List<SkillFileReferenceItem> refs = skillMapper.selectSkillFileReferences(skillId);
            if (refs == null || refs.isEmpty()) {
                return Map.of();
            }
            Map<String, String> resources = new HashMap<>();
            for (SkillFileReferenceItem ref : refs) {
                String filename = ref.filename();
                if (filename == null) {
                    continue;
                }
                SkillFile file = skillMapper.selectFileByUserIdAndFilename(userId, filename);
                if (file == null || file.getStoragePath() == null) {
                    continue;
                }
                try {
                    // DB 存的是相对路径 {userId}/{filename},须拼 baseDir 解析成绝对路径
                    // (与 SkillFileService.resolveStoragePath 一致,历史反斜杠记录归一化成 "/")
                    // Paths.get 在第二参为绝对路径时忽略 baseDir,故历史绝对路径记录同样兼容。
                    Path path = Paths.get(baseDir, file.getStoragePath().replace('\\', '/'));
                    // key 用绝对路径(统一成正斜杠,避免 Windows 反斜杠在 JSON 工具参数里转义出错)。
                    // SkillLoadTool.loadOne 按 key 精确匹配(无 sanitize/normalize),故 LLM 须经
                    // load_skill_through_path(path=<此绝对路径>) 读取;找不到时工具会回显 Available resources 列表。
                    String fullPath = path.toAbsolutePath().toString().replace('\\', '/');
                    resources.put(fullPath, Files.readString(path));
                } catch (IOException e) {
                    log.warn("DatabaseSkillRepository: 读取文件 '{}' 失败 (skill={}): {}",
                            filename, skillId, e.getMessage());
                }
            }
            return resources;
        } catch (Exception e) {
            log.warn("DatabaseSkillRepository: 加载 skill={} 文件资源失败: {}", skillId, e.getMessage());
            return Map.of();
        }
    }
}
