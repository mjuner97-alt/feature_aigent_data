package com.agentscopea2a.v2.skills;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentscopea2a.v2.skillManager.entity.Skill;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import io.agentscope.core.skill.AgentSkill;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class DatabaseSkillRepositoryTest {

    @Mock
    private SkillMapper skillMapper;

    private DatabaseSkillRepository repo;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        repo = new DatabaseSkillRepository(skillMapper, "user_001");
    }

    // ==================== getSkill ====================

    @Test
    void getSkill_returnsAgentSkill_whenFound() {
        Skill skill = Skill.builder()
                .id(1L)
                .name("display name")
                .description("a test skill")
                .content("# Body\nsome content")
                .retrievalName("usr_user_001_test_skill")
                .ownerUserId("user_001")
                .status("ACTIVE")
                .build();
        when(skillMapper.selectByRetrievalNameAndOwner("usr_user_001_test_skill", "user_001"))
                .thenReturn(skill);

        AgentSkill result = repo.getSkill("usr_user_001_test_skill");

        assertNotNull(result);
        assertEquals("usr_user_001_test_skill", result.getName());
        assertEquals("a test skill", result.getDescription());
        assertEquals("# Body\nsome content", result.getSkillContent());
        assertEquals("user_generated", result.getSource());
    }

    @Test
    void getSkill_returnsNull_whenNotFound() {
        when(skillMapper.selectByRetrievalNameAndOwner(anyString(), anyString()))
                .thenReturn(null);

        AgentSkill result = repo.getSkill("nonexistent");
        assertNull(result);
    }

    @Test
    void getSkill_returnsNull_whenUserIdIsNull() {
        DatabaseSkillRepository nullUserRepo = new DatabaseSkillRepository(skillMapper, null);
        AgentSkill result = nullUserRepo.getSkill("any_skill");
        assertNull(result);
    }

    // ==================== getAllSkillNames ====================

    @Test
    void getAllSkillNames_returnsNames() {
        when(skillMapper.selectActiveRetrievalNamesByOwner("user_001"))
                .thenReturn(List.of("skill_a", "skill_b"));

        List<String> names = repo.getAllSkillNames();

        assertEquals(2, names.size());
        assertTrue(names.contains("skill_a"));
        assertTrue(names.contains("skill_b"));
    }

    @Test
    void getAllSkillNames_returnsEmpty_whenUserIdIsNull() {
        DatabaseSkillRepository nullUserRepo = new DatabaseSkillRepository(skillMapper, null);
        List<String> names = nullUserRepo.getAllSkillNames();
        assertTrue(names.isEmpty());
    }

    // ==================== getAllSkills ====================

    @Test
    void getAllSkills_returnsAgentSkills() {
        Skill s1 = Skill.builder()
                .name("n1").description("d1").content("c1")
                .retrievalName("r1").ownerUserId("user_001").status("ACTIVE").build();
        Skill s2 = Skill.builder()
                .name("n2").description("d2").content("c2")
                .retrievalName("r2").ownerUserId("user_001").status("ACTIVE").build();
        when(skillMapper.selectActiveByOwner("user_001"))
                .thenReturn(List.of(s1, s2));

        List<AgentSkill> skills = repo.getAllSkills();

        assertEquals(2, skills.size());
        assertEquals("r1", skills.get(0).getName());
        assertEquals("r2", skills.get(1).getName());
    }

    @Test
    void getAllSkills_returnsEmpty_whenUserIdIsNull() {
        DatabaseSkillRepository nullUserRepo = new DatabaseSkillRepository(skillMapper, null);
        assertTrue(nullUserRepo.getAllSkills().isEmpty());
    }

    // ==================== skillExists ====================

    @Test
    void skillExists_returnsTrue_whenExists() {
        when(skillMapper.existsByRetrievalNameAndOwner("skill_a", "user_001"))
                .thenReturn(true);
        assertTrue(repo.skillExists("skill_a"));
    }

    @Test
    void skillExists_returnsFalse_whenNotExists() {
        when(skillMapper.existsByRetrievalNameAndOwner(anyString(), anyString()))
                .thenReturn(false);
        assertFalse(repo.skillExists("nonexistent"));
    }

    @Test
    void skillExists_returnsFalse_whenUserIdIsNull() {
        DatabaseSkillRepository nullUserRepo = new DatabaseSkillRepository(skillMapper, null);
        assertFalse(nullUserRepo.skillExists("any"));
    }

    // ==================== save ====================

    @Test
    void save_insertsNewSkill_whenNotExists() {
        AgentSkill newSkill = AgentSkill.builder()
                .name("new_skill").description("desc").skillContent("body").build();
        when(skillMapper.existsByRetrievalNameAndOwner("new_skill", "user_001"))
                .thenReturn(false);

        boolean result = repo.save(List.of(newSkill), false);

        assertTrue(result);
        verify(skillMapper).insertSkill(argThat(s ->
                "new_skill".equals(s.getRetrievalName())
                && "user_001".equals(s.getOwnerUserId())
                && "ACTIVE".equals(s.getStatus())));
    }

    @Test
    void save_skipsExistingSkill_whenForceFalse() {
        AgentSkill existing = AgentSkill.builder()
                .name("existing").description("desc").skillContent("body").build();
        when(skillMapper.existsByRetrievalNameAndOwner("existing", "user_001"))
                .thenReturn(true);

        boolean result = repo.save(List.of(existing), false);

        assertTrue(result);
        verify(skillMapper, never()).insertSkill(any());
        verify(skillMapper, never()).updateByRetrievalNameAndOwner(any());
    }

    @Test
    void save_updatesExistingSkill_whenForceTrue() {
        AgentSkill existing = AgentSkill.builder()
                .name("existing").description("new desc").skillContent("new body").build();
        when(skillMapper.existsByRetrievalNameAndOwner("existing", "user_001"))
                .thenReturn(true);

        boolean result = repo.save(List.of(existing), true);

        assertTrue(result);
        verify(skillMapper).updateByRetrievalNameAndOwner(argThat(s ->
                "existing".equals(s.getRetrievalName())
                && "new desc".equals(s.getDescription())));
        verify(skillMapper, never()).insertSkill(any());
    }

    @Test
    void save_returnsFalse_whenUserIdIsNull() {
        DatabaseSkillRepository nullUserRepo = new DatabaseSkillRepository(skillMapper, null);
        AgentSkill skill = AgentSkill.builder()
                .name("x").description("d").skillContent("c").build();
        assertFalse(nullUserRepo.save(List.of(skill), false));
    }

    // ==================== delete ====================

    @Test
    void delete_returnsTrue_whenSkillDeleted() {
        when(skillMapper.softDeleteByRetrievalNameAndOwner("skill_a", "user_001"))
                .thenReturn(1);
        assertTrue(repo.delete("skill_a"));
    }

    @Test
    void delete_returnsFalse_whenSkillNotFound() {
        when(skillMapper.softDeleteByRetrievalNameAndOwner(anyString(), anyString()))
                .thenReturn(0);
        assertFalse(repo.delete("nonexistent"));
    }

    @Test
    void delete_returnsFalse_whenUserIdIsNull() {
        DatabaseSkillRepository nullUserRepo = new DatabaseSkillRepository(skillMapper, null);
        assertFalse(nullUserRepo.delete("any"));
    }

    // ==================== 元信息方法 ====================

    @Test
    void getSource_returnsDatabase() {
        assertEquals("database", repo.getSource());
    }

    @Test
    void isWriteable_returnsTrue_byDefault() {
        assertTrue(repo.isWriteable());
    }

    @Test
    void setWriteable_changesState() {
        repo.setWriteable(false);
        assertFalse(repo.isWriteable());
    }

    @Test
    void getRepositoryInfo_returnsNotNull() {
        assertNotNull(repo.getRepositoryInfo());
    }
}
