# Skill 点赞与广场前端(核心闭环) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 analysis-project(Spring Boot + MyBatis + Vue3)中实现 Skill 点赞功能与"Skill 广场"前端,支撑"全部 / 我使用的 / 我点赞的 / 我创建的 / 热门榜 / 分类"6 个视图,点赞多的 Skill 优先排在最前面。

**Architecture:** 后端按既有 MySQL 数据源 + MyBatis(XML mapper)+ Flyway 模式新增 `skill_manage` / `skill_like` / `skill_reference` 三表与 REST 控制器;点赞用独立 `skill_like` 表 + `skill_manage.like_count` 冗余计数(原子 ±1、唯一约束防重、幂等 toggle),排序走 `idx_like_rank`。前端复用现有 Vue3+Vite SPA,新增 `SkillShell` 布局与 `/skills/*` 路由,沿用"构建进 `src/main/resources/static`、单 jar 部署"的合并模式。

**Tech Stack:** Java 17 / Spring Boot(spring-boot-starter-web)/ MyBatis(mybatis-spring-boot-starter)/ Flyway / MySQL / Lombok / JUnit5 + Mockito;Vue 3 + vue-router + Vite + TypeScript。

## Global Constraints

- **不提交代码(用户明确要求):** 任何任务末尾**不执行** `git add/commit/push`。每个任务最后一步改为"检查点:跑全量测试通过后暂停,由用户决定是否提交"。
- **userId 取法:** 本工程未启用 Spring Security(偏离 spec §7.5)。所有写操作与个性化视图的 `userId` 经请求头 `X-User-Id` 传入(`@RequestHeader("X-User-Id")`)。后续接入鉴权时再替换。
- **数据源:** MySQL 为 `@Primary`,`@Transactional` 默认即 `mysqlTransactionManager`,无需显式指定。
- **包路径(强约束):** 实体放 `com.agentscopea2a.entity`(MyBatis `typeAliasesPackage`);Mapper 接口放 `com.agentscopea2a.mapper.mysql`(`@MapperScan` basePackages);Mapper XML 放 `src/main/resources/mybatis/mapper/mysql/`;Flyway 迁移放 `src/main/resources/db/migration/`,命名 `V20260723.x__*.sql`,DDL 统一 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`。
- **License header:** 所有新建 `.java` 文件沿用现有文件的 Apache 2.0 头(见 `UrlShortenerMapper.java`),实现时复制该头;本计划代码块为节省篇幅省略头部。
- **本计划范围(核心闭环):** 只实现 `skill_manage`(CRUD+列表)、`skill_like`(点赞)、`skill_reference`(引用,撑"我使用的")三表及其 API + 前端 6 视图 + 详情页。**不实现** `skill_publish`/审批/`skill_user_disable`/`skill_version_history`/`skill_operation_history`;因此**可用性恒为"可用"**(所有 `ACTIVE` Skill 的 `available=true`),可用性徽章与"待我审批"视图留后续计划。
- **排序:** 默认 `like_count DESC, updated_at DESC`;点赞幂等(重复 `POST` 不重复计数)。
- **前端构建:** Vite `outDir` 已为 `../src/main/resources/static`;新增 `/api` 代理到 `http://localhost:8081`;前端无测试框架,前端任务用**人工验证**(启 dev server 看页面)。

## File Structure

**后端(新建):**
- `src/main/resources/db/migration/V20260723.1__create_skill_manage.sql` — skill_manage 表(含 like_count + idx_like_rank)
- `src/main/resources/db/migration/V20260723.2__create_skill_like.sql` — skill_like 表
- `src/main/resources/db/migration/V20260723.3__create_skill_reference.sql` — skill_reference 表
- `src/main/java/com/agentscopea2a/entity/Skill.java` — Skill 实体
- `src/main/java/com/agentscopea2a/entity/SkillLike.java` — 点赞记录实体
- `src/main/java/com/agentscopea2a/entity/SkillReference.java` — 引用记录实体
- `src/main/java/com/agentscopea2a/mapper/mysql/SkillManageMapper.java` + `src/main/resources/mybatis/mapper/mysql/SkillManageMapper.xml`
- `src/main/java/com/agentscopea2a/mapper/mysql/SkillLikeMapper.java` + `src/main/resources/mybatis/mapper/mysql/SkillLikeMapper.xml`
- `src/main/java/com/agentscopea2a/mapper/mysql/SkillReferenceMapper.java` + `src/main/resources/mybatis/mapper/mysql/SkillReferenceMapper.xml`
- `src/main/java/com/agentscopea2a/dto/SkillListItem.java` — 列表行 DTO(含 likeCount/liked/used/available/rank)
- `src/main/java/com/agentscopea2a/dto/SkillListQuery.java` — 列表查询参数(view/sort/category/tag/keyword/limit/offset)
- `src/main/java/com/agentscopea2a/dto/LikeStatus.java` — 点赞状态(liked + likeCount)
- `src/main/java/com/agentscopea2a/v2/service/SkillService.java` — CRUD + 列表
- `src/main/java/com/agentscopea2a/v2/service/SkillLikeService.java` — 点赞幂等逻辑
- `src/main/java/com/agentscopea2a/v2/service/SkillReferenceService.java` — 引用逻辑
- `src/main/java/com/agentscopea2a/v2/controller/SkillController.java`
- `src/main/java/com/agentscopea2a/v2/controller/SkillLikeController.java`
- `src/main/java/com/agentscopea2a/v2/controller/SkillReferenceController.java`

**后端(测试,新建):**
- `src/test/java/com/agentscopea2a/v2/service/SkillLikeServiceTest.java`
- `src/test/java/com/agentscopea2a/v2/service/SkillServiceTest.java`
- `src/test/java/com/agentscopea2a/v2/service/SkillReferenceServiceTest.java`

**前端(新建/修改):**
- `frontend/src/api/skill.ts`(新建)
- `frontend/src/components/SkillShell.vue`(新建)— 左侧导航布局
- `frontend/src/components/SkillList.vue`(新建)— 网格/列表渲染 + 密度切换
- `frontend/src/components/SkillCard.vue`(新建)— 网格卡片
- `frontend/src/components/SkillRow.vue`(新建)— 列表行
- `frontend/src/pages/skill/AllSkillsPage.vue` / `UsedSkillsPage.vue` / `LikedSkillsPage.vue` / `CreatedSkillsPage.vue` / `PopularSkillsPage.vue` / `CategoryBrowsePage.vue` / `SkillDetailPage.vue`(新建)
- `frontend/src/main.ts`(修改:加路由)
- `frontend/vite.config.ts`(修改:加 `/api` 代理)

---

### Task 1: skill_manage 表 + Skill 实体 + Mapper + CRUD

**Files:**
- Create: `src/main/resources/db/migration/V20260723.1__create_skill_manage.sql`
- Create: `src/main/java/com/agentscopea2a/entity/Skill.java`
- Create: `src/main/java/com/agentscopea2a/mapper/mysql/SkillManageMapper.java`
- Create: `src/main/resources/mybatis/mapper/mysql/SkillManageMapper.xml`
- Create: `src/main/java/com/agentscopea2a/v2/service/SkillService.java`
- Create: `src/main/java/com/agentscopea2a/v2/controller/SkillController.java`
- Test: `src/test/java/com/agentscopea2a/v2/service/SkillServiceTest.java`

**Interfaces:**
- Consumes: 无(基础表)
- Produces: `Skill` 实体;`SkillManageMapper`(insert/selectById/update/softDelete/existsByName/selectLikeCount/incrementLikeCount/decrementLikeCount);`SkillService.create/get/getList/update/delete`;`SkillController` `/api/skills` CRUD。

- [ ] **Step 1: 写 Flyway 迁移**

`src/main/resources/db/migration/V20260723.1__create_skill_manage.sql`:
```sql
-- ============================================================================
-- skill_manage: Skill 主表(含冗余 like_count 与点赞排序索引)
-- ============================================================================

CREATE TABLE IF NOT EXISTS skill_manage (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  description TEXT NULL,
  content TEXT NULL,
  category VARCHAR(64) NULL,
  tags VARCHAR(512) NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  like_count BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP NULL DEFAULT NULL,
  UNIQUE KEY uk_name (name),
  KEY idx_owner (owner_user_id),
  KEY idx_status (status),
  KEY idx_like_rank (like_count DESC, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: 写 Skill 实体**

`src/main/java/com/agentscopea2a/entity/Skill.java`:
```java
package com.agentscopea2a.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    private Long id;
    private String name;
    private String description;
    private String content;
    private String category;
    private String tags;
    private String ownerUserId;
    private String status;
    private Long likeCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
```

- [ ] **Step 3: 写 SkillManageMapper 接口**

`src/main/java/com/agentscopea2a/mapper/mysql/SkillManageMapper.java`:
```java
package com.agentscopea2a.mapper.mysql;

import com.agentscopea2a.entity.Skill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SkillManageMapper {

    int insert(Skill skill);

    Skill selectById(@Param("id") Long id);

    int update(Skill skill);

    int softDelete(@Param("id") Long id);

    boolean existsByName(@Param("name") String name);

    Long selectLikeCount(@Param("id") Long id);

    int incrementLikeCount(@Param("id") Long id);

    int decrementLikeCount(@Param("id") Long id);

    List<Skill> selectByIds(@Param("ids") List<Long> ids);
}
```
说明:列表查询 `selectList` 放在 Task 4(依赖 `SkillListQuery`)。

- [ ] **Step 4: 写 SkillManageMapper.xml**

`src/main/resources/mybatis/mapper/mysql/SkillManageMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.agentscopea2a.mapper.mysql.SkillManageMapper">

    <resultMap id="BaseResultMap" type="com.agentscopea2a.entity.Skill">
        <id column="id" property="id"/>
        <result column="name" property="name"/>
        <result column="description" property="description"/>
        <result column="content" property="content"/>
        <result column="category" property="category"/>
        <result column="tags" property="tags"/>
        <result column="owner_user_id" property="ownerUserId"/>
        <result column="status" property="status"/>
        <result column="like_count" property="likeCount"/>
        <result column="created_at" property="createdAt"/>
        <result column="updated_at" property="updatedAt"/>
        <result column="deleted_at" property="deletedAt"/>
    </resultMap>

    <sql id="cols">id, name, description, content, category, tags, owner_user_id, status, like_count, created_at, updated_at, deleted_at</sql>

    <insert id="insert" parameterType="com.agentscopea2a.entity.Skill" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO skill_manage (name, description, content, category, tags, owner_user_id, status, like_count)
        VALUES (#{name}, #{description}, #{content}, #{category}, #{tags}, #{ownerUserId}, #{status}, 0)
    </insert>

    <select id="selectById" resultMap="BaseResultMap">
        SELECT <include refid="cols"/> FROM skill_manage WHERE id = #{id}
    </select>

    <update id="update" parameterType="com.agentscopea2a.entity.Skill">
        UPDATE skill_manage
        SET name=#{name}, description=#{description}, content=#{content},
            category=#{category}, tags=#{tags}, status=#{status}
        WHERE id = #{id}
    </update>

    <update id="softDelete">
        UPDATE skill_manage SET status='DELETED', deleted_at=NOW() WHERE id = #{id}
    </update>

    <select id="existsByName" resultType="boolean">
        SELECT COUNT(1) > 0 FROM skill_manage WHERE name = #{name} AND status &lt;&gt; 'DELETED'
    </select>

    <select id="selectLikeCount" resultType="java.lang.Long">
        SELECT like_count FROM skill_manage WHERE id = #{id}
    </select>

    <update id="incrementLikeCount">
        UPDATE skill_manage SET like_count = like_count + 1 WHERE id = #{id}
    </update>

    <update id="decrementLikeCount">
        UPDATE skill_manage SET like_count = GREATEST(like_count - 1, 0) WHERE id = #{id}
    </update>

    <select id="selectByIds" resultMap="BaseResultMap">
        SELECT <include refid="cols"/> FROM skill_manage
        WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
    </select>

</mapper>
```

- [ ] **Step 5: 写 SkillService(CRUD 部分,列表方法 Task 4 再加)**

`src/main/java/com/agentscopea2a/v2/service/SkillService.java`:
```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class SkillService {

    private final SkillManageMapper skillManageMapper;

    public SkillService(SkillManageMapper skillManageMapper) {
        this.skillManageMapper = skillManageMapper;
    }

    @Transactional
    public Skill create(Skill skill, String ownerUserId) {
        if (skillManageMapper.existsByName(skill.getName())) {
            throw new IllegalStateException("SkillNameConflict: " + skill.getName());
        }
        skill.setOwnerUserId(ownerUserId);
        skill.setStatus("ACTIVE");
        skill.setLikeCount(0L);
        skill.setCreatedAt(LocalDateTime.now());
        skill.setUpdatedAt(LocalDateTime.now());
        skillManageMapper.insert(skill);
        return skillManageMapper.selectById(skill.getId());
    }

    public Skill get(Long id) {
        Skill s = skillManageMapper.selectById(id);
        if (s == null || "DELETED".equals(s.getStatus())) {
            throw new IllegalStateException("SkillNotFound: " + id);
        }
        return s;
    }

    @Transactional
    public Skill update(Long id, Skill patch, String userId) {
        Skill s = get(id);
        if (!s.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("SkillAccessDenied: " + id);
        }
        if (patch.getName() != null && !patch.getName().equals(s.getName())
                && skillManageMapper.existsByName(patch.getName())) {
            throw new IllegalStateException("SkillNameConflict: " + patch.getName());
        }
        if (patch.getName() != null) s.setName(patch.getName());
        if (patch.getDescription() != null) s.setDescription(patch.getDescription());
        if (patch.getContent() != null) s.setContent(patch.getContent());
        if (patch.getCategory() != null) s.setCategory(patch.getCategory());
        if (patch.getTags() != null) s.setTags(patch.getTags());
        s.setUpdatedAt(LocalDateTime.now());
        skillManageMapper.update(s);
        return skillManageMapper.selectById(id);
    }

    @Transactional
    public void delete(Long id, String userId) {
        Skill s = get(id);
        if (!s.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("SkillAccessDenied: " + id);
        }
        skillManageMapper.softDelete(id);
    }
}
```
注:`IllegalStateException` 为本计划占位异常,Task 末尾不引入自定义异常类(保持轻量);后续可替换为 spec §8 的自定义异常 + `@ControllerAdvice`。控制器层将其映射为对应 HTTP 状态。

- [ ] **Step 6: 写 SkillController(CRUD)**

`src/main/java/com/agentscopea2a/v2/controller/SkillController.java`:
```java
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.v2.service.SkillService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skills")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @PostMapping
    public Skill create(@RequestBody Skill skill, @RequestHeader("X-User-Id") String userId) {
        return skillService.create(skill, userId);
    }

    @GetMapping("/{id}")
    public Skill get(@PathVariable Long id) {
        return skillService.get(id);
    }

    @PutMapping("/{id}")
    public Skill update(@PathVariable Long id, @RequestBody Skill patch,
                        @RequestHeader("X-User-Id") String userId) {
        return skillService.update(id, patch, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        skillService.delete(id, userId);
    }
}
```
注:`GET /api/skills`(列表)在 Task 4 加。

- [ ] **Step 7: 写失败测试(SkillService CRUD)**

`src/test/java/com/agentscopea2a/v2/service/SkillServiceTest.java`:
```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillServiceTest {

    private SkillManageMapper mapper;
    private SkillService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SkillManageMapper.class);
        service = new SkillService(mapper);
    }

    @Test
    void create_sets_owner_status_active_likeCount0_and_inserts() {
        when(mapper.existsByName("SQL优化")).thenReturn(false);
        Skill input = Skill.builder().name("SQL优化").description("d").content("c").category("数据").tags("#sql").build();
        Skill persisted = Skill.builder().id(1L).name("SQL优化").ownerUserId("u1").status("ACTIVE").likeCount(0L).build();
        when(mapper.selectById(any())).thenReturn(persisted);

        Skill result = service.create(input, "u1");

        ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerUserId()).isEqualTo("u1");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().getLikeCount()).isZero();
        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void create_rejects_duplicate_name() {
        when(mapper.existsByName("SQL优化")).thenReturn(true);
        Skill input = Skill.builder().name("SQL优化").build();
        assertThatThrownBy(() -> service.create(input, "u1"))
                .hasMessageContaining("SkillNameConflict");
    }

    @Test
    void update_denies_non_owner() {
        Skill s = Skill.builder().id(1L).name("n").ownerUserId("u1").status("ACTIVE").build();
        when(mapper.selectById(1L)).thenReturn(s);
        assertThatThrownBy(() -> service.update(1L, Skill.builder().name("n2").build(), "u2"))
                .hasMessageContaining("SkillAccessDenied");
    }

    @Test
    void delete_soft_deletes_for_owner() {
        Skill s = Skill.builder().id(1L).name("n").ownerUserId("u1").status("ACTIVE").build();
        when(mapper.selectById(1L)).thenReturn(s);
        service.delete(1L, "u1");
        verify(mapper).softDelete(eq(1L));
    }
}
```

- [ ] **Step 8: 运行测试验证失败(方法尚不存在编译错误已被实现覆盖) → 直接运行验证通过**

Run: `mvn -q -pl . test -Dtest=SkillServiceTest`
Expected: PASS(本任务实现与测试同步完成;TDD 顺序已由 Step 5/6 实现保证)。

- [ ] **Step 9: 检查点(不提交)**

Run: `mvn -q test -Dtest=SkillServiceTest`
Expected: PASS。暂停,由用户决定是否提交。

---

### Task 2: skill_like 表 + 点赞(幂等 + like_count 原子增减)

**Files:**
- Create: `src/main/resources/db/migration/V20260723.2__create_skill_like.sql`
- Create: `src/main/java/com/agentscopea2a/entity/SkillLike.java`
- Create: `src/main/java/com/agentscopea2a/mapper/mysql/SkillLikeMapper.java` + `SkillLikeMapper.xml`
- Create: `src/main/java/com/agentscopea2a/dto/LikeStatus.java`
- Create: `src/main/java/com/agentscopea2a/v2/service/SkillLikeService.java`
- Create: `src/main/java/com/agentscopea2a/v2/controller/SkillLikeController.java`
- Test: `src/test/java/com/agentscopea2a/v2/service/SkillLikeServiceTest.java`

**Interfaces:**
- Consumes: `SkillManageMapper.selectLikeCount/incrementLikeCount/decrementLikeCount`(Task 1),`SkillService.get`(校验 Skill 存在且 ACTIVE)
- Produces: `SkillLikeService.like(skillId,userId)->LikeStatus` / `unlike(...)` / `getStatus(...)`;`SkillLikeMapper.selectByUserSkill/insert/deleteByUserSkill/selectLikedSkillIds`;`SkillLikeController` `/api/skills/{id}/like`。

- [ ] **Step 1: 写迁移**

`src/main/resources/db/migration/V20260723.2__create_skill_like.sql`:
```sql
-- ============================================================================
-- skill_like: 点赞记录表(每用户每 Skill 仅一条,唯一约束防重)
-- ============================================================================

CREATE TABLE IF NOT EXISTS skill_like (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  skill_id BIGINT NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_skill (user_id, skill_id),
  KEY idx_skill (skill_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: 写 SkillLike 实体**

`src/main/java/com/agentscopea2a/entity/SkillLike.java`:
```java
package com.agentscopea2a.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillLike {
    private Long id;
    private Long skillId;
    private String userId;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: 写 LikeStatus DTO**

`src/main/java/com/agentscopea2a/dto/LikeStatus.java`:
```java
package com.agentscopea2a.dto;

public record LikeStatus(boolean liked, long likeCount) {}
```

- [ ] **Step 4: 写 SkillLikeMapper 接口**

`src/main/java/com/agentscopea2a/mapper/mysql/SkillLikeMapper.java`:
```java
package com.agentscopea2a.mapper.mysql;

import com.agentscopea2a.entity.SkillLike;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

@Mapper
public interface SkillLikeMapper {

    int insert(SkillLike like);

    SkillLike selectByUserSkill(@Param("userId") String userId, @Param("skillId") Long skillId);

    int deleteByUserSkill(@Param("userId") String userId, @Param("skillId") Long skillId);

    /** 当前用户在给定 skillId 集合中已点赞的 skillId(用于列表行 liked 标记批量计算)。 */
    Set<Long> selectLikedSkillIds(@Param("userId") String userId, @Param("ids") List<Long> ids);
}
```

- [ ] **Step 5: 写 SkillLikeMapper.xml**

`src/main/resources/mybatis/mapper/mysql/SkillLikeMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.agentscopea2a.mapper.mysql.SkillLikeMapper">

    <insert id="insert" parameterType="com.agentscopea2a.entity.SkillLike"
            useGeneratedKeys="true" keyProperty="id">
        INSERT INTO skill_like (skill_id, user_id, created_at)
        VALUES (#{skillId}, #{userId}, #{createdAt})
    </insert>

    <select id="selectByUserSkill" resultType="com.agentscopea2a.entity.SkillLike">
        SELECT id, skill_id, user_id, created_at FROM skill_like
        WHERE user_id = #{userId} AND skill_id = #{skillId}
    </select>

    <delete id="deleteByUserSkill">
        DELETE FROM skill_like WHERE user_id = #{userId} AND skill_id = #{skillId}
    </delete>

    <select id="selectLikedSkillIds" resultType="java.lang.Long">
        SELECT skill_id FROM skill_like
        WHERE user_id = #{userId}
        AND skill_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
    </select>

</mapper>
```

- [ ] **Step 6: 写失败测试(SkillLikeService 幂等)**

`src/test/java/com/agentscopea2a/v2/service/SkillLikeServiceTest.java`:
```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.dto.LikeStatus;
import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.mapper.mysql.SkillLikeMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillLikeServiceTest {

    private SkillLikeMapper likeMapper;
    private SkillManageMapper manageMapper;
    private SkillService skillService;
    private SkillLikeService service;

    @BeforeEach
    void setUp() {
        likeMapper = mock(SkillLikeMapper.class);
        manageMapper = mock(SkillManageMapper.class);
        skillService = mock(SkillService.class);
        service = new SkillLikeService(likeMapper, manageMapper, skillService);
    }

    private Skill activeSkill() {
        return Skill.builder().id(10L).name("n").status("ACTIVE").build();
    }

    @Test
    void like_when_not_liked_inserts_and_increments() {
        when(skillService.get(10L)).thenReturn(activeSkill());
        when(likeMapper.selectByUserSkill("u1", 10L)).thenReturn(null);
        when(manageMapper.selectLikeCount(10L)).thenReturn(5L);

        LikeStatus status = service.like(10L, "u1");

        assertThat(status.liked()).isTrue();
        assertThat(status.likeCount()).isEqualTo(5L);
        verify(likeMapper).insert(any());
        verify(manageMapper).incrementLikeCount(10L);
    }

    @Test
    void like_when_already_liked_is_idempotent_no_increment() {
        when(skillService.get(10L)).thenReturn(activeSkill());
        when(likeMapper.selectByUserSkill("u1", 10L)).thenReturn(
                com.agentscopea2a.entity.SkillLike.builder().id(1L).skillId(10L).userId("u1").build());
        when(manageMapper.selectLikeCount(10L)).thenReturn(5L);

        LikeStatus status = service.like(10L, "u1");

        assertThat(status.liked()).isTrue();
        verify(likeMapper, never()).insert(any());
        verify(manageMapper, never()).incrementLikeCount(10L);
    }

    @Test
    void like_catches_duplicate_key_race_as_idempotent() {
        when(skillService.get(10L)).thenReturn(activeSkill());
        when(likeMapper.selectByUserSkill("u1", 10L)).thenReturn(null);
        when(likeMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_user_skill"));
        when(manageMapper.selectLikeCount(10L)).thenReturn(5L);

        LikeStatus status = service.like(10L, "u1");

        assertThat(status.liked()).isTrue();
        verify(manageMapper, never()).incrementLikeCount(10L);
    }

    @Test
    void unlike_when_liked_deletes_and_decrements() {
        when(skillService.get(10L)).thenReturn(activeSkill());
        when(likeMapper.selectByUserSkill("u1", 10L)).thenReturn(
                com.agentscopea2a.entity.SkillLike.builder().id(1L).skillId(10L).userId("u1").build());
        when(manageMapper.selectLikeCount(10L)).thenReturn(4L);

        LikeStatus status = service.unlike(10L, "u1");

        assertThat(status.liked()).isFalse();
        assertThat(status.likeCount()).isEqualTo(4L);
        verify(likeMapper).deleteByUserSkill("u1", 10L);
        verify(manageMapper).decrementLikeCount(10L);
    }

    @Test
    void unlike_when_not_liked_is_idempotent_no_decrement() {
        when(skillService.get(10L)).thenReturn(activeSkill());
        when(likeMapper.selectByUserSkill("u1", 10L)).thenReturn(null);
        when(manageMapper.selectLikeCount(10L)).thenReturn(5L);

        LikeStatus status = service.unlike(10L, "u1");

        assertThat(status.liked()).isFalse();
        verify(likeMapper, never()).deleteByUserSkill(eq("u1"), eq(10L));
        verify(manageMapper, never()).decrementLikeCount(10L);
    }
}
```

- [ ] **Step 7: 运行测试验证失败**

Run: `mvn -q test -Dtest=SkillLikeServiceTest`
Expected: 编译失败(`SkillLikeService` 未创建)。

- [ ] **Step 8: 写 SkillLikeService 实现**

`src/main/java/com/agentscopea2a/v2/service/SkillLikeService.java`:
```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.dto.LikeStatus;
import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.entity.SkillLike;
import com.agentscopea2a.mapper.mysql.SkillLikeMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class SkillLikeService {

    private static final Logger log = LoggerFactory.getLogger(SkillLikeService.class);

    private final SkillLikeMapper likeMapper;
    private final SkillManageMapper manageMapper;
    private final SkillService skillService;

    public SkillLikeService(SkillLikeMapper likeMapper, SkillManageMapper manageMapper, SkillService skillService) {
        this.likeMapper = likeMapper;
        this.manageMapper = manageMapper;
        this.skillService = skillService;
    }

    @Transactional
    public LikeStatus like(Long skillId, String userId) {
        assertActive(skillId);
        if (likeMapper.selectByUserSkill(userId, skillId) != null) {
            return new LikeStatus(true, currentCount(skillId));
        }
        try {
            likeMapper.insert(SkillLike.builder()
                    .skillId(skillId).userId(userId).createdAt(LocalDateTime.now()).build());
            manageMapper.incrementLikeCount(skillId);
        } catch (DuplicateKeyException e) {
            log.debug("concurrent like race, treat as idempotent: skill={} user={}", skillId, userId);
        }
        return new LikeStatus(true, currentCount(skillId));
    }

    @Transactional
    public LikeStatus unlike(Long skillId, String userId) {
        assertActive(skillId);
        if (likeMapper.selectByUserSkill(userId, skillId) == null) {
            return new LikeStatus(false, currentCount(skillId));
        }
        likeMapper.deleteByUserSkill(userId, skillId);
        manageMapper.decrementLikeCount(skillId);
        return new LikeStatus(false, currentCount(skillId));
    }

    public LikeStatus getStatus(Long skillId, String userId) {
        boolean liked = likeMapper.selectByUserSkill(userId, skillId) != null;
        return new LikeStatus(liked, currentCount(skillId));
    }

    private void assertActive(Long skillId) {
        Skill s = skillService.get(skillId);
        if (!"ACTIVE".equals(s.getStatus())) {
            throw new IllegalStateException("SkillNotActive: " + skillId);
        }
    }

    private long currentCount(Long skillId) {
        Long c = manageMapper.selectLikeCount(skillId);
        return c == null ? 0L : c;
    }
}
```

- [ ] **Step 9: 运行测试验证通过**

Run: `mvn -q test -Dtest=SkillLikeServiceTest`
Expected: PASS(5 个测试全绿)。

- [ ] **Step 10: 写 SkillLikeController**

`src/main/java/com/agentscopea2a/v2/controller/SkillLikeController.java`:
```java
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.dto.LikeStatus;
import com.agentscopea2a.v2.service.SkillLikeService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skills")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillLikeController {

    private final SkillLikeService likeService;

    public SkillLikeController(SkillLikeService likeService) {
        this.likeService = likeService;
    }

    @PostMapping("/{id}/like")
    public LikeStatus like(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        return likeService.like(id, userId);
    }

    @DeleteMapping("/{id}/like")
    public LikeStatus unlike(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        return likeService.unlike(id, userId);
    }

    @GetMapping("/{id}/like")
    public LikeStatus status(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        return likeService.getStatus(id, userId);
    }
}
```

- [ ] **Step 11: 检查点(不提交)**

Run: `mvn -q test -Dtest=SkillLikeServiceTest,SkillServiceTest`
Expected: PASS。暂停,由用户决定是否提交。

---

### Task 3: skill_reference 表 + 引用 API(撑"我使用的"视图)

**Files:**
- Create: `src/main/resources/db/migration/V20260723.3__create_skill_reference.sql`
- Create: `src/main/java/com/agentscopea2a/entity/SkillReference.java`
- Create: `src/main/java/com/agentscopea2a/mapper/mysql/SkillReferenceMapper.java` + `SkillReferenceMapper.xml`
- Create: `src/main/java/com/agentscopea2a/v2/service/SkillReferenceService.java`
- Create: `src/main/java/com/agentscopea2a/v2/controller/SkillReferenceController.java`
- Test: `src/test/java/com/agentscopea2a/v2/service/SkillReferenceServiceTest.java`

**Interfaces:**
- Consumes: `SkillService.get`(校验目标 Skill 存在)
- Produces: `SkillReferenceService.reference/unreference/listMine`;`SkillReferenceMapper.insert/deleteByCreatorTarget/existsByCreatorTarget/selectUsedSkillIds`;控制器 `POST/DELETE /api/skills/{id}/reference`、`GET /api/skills/my-references`。语义:用户 A 引用 Skill S -> 写入 `(source=S, target=S, creator=A)`(与 spec §5.6 一致)。

- [ ] **Step 1: 写迁移**

`src/main/resources/db/migration/V20260723.3__create_skill_reference.sql`:
```sql
-- ============================================================================
-- skill_reference: 引用关系表(creator=引用者;source=target=被引用 Skill,与 spec §5.6 一致)
-- "我使用的 Skill" = SELECT target_skill_id WHERE creator = 当前用户
-- ============================================================================

CREATE TABLE IF NOT EXISTS skill_reference (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_skill_id BIGINT NOT NULL,
  target_skill_id BIGINT NOT NULL,
  creator VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_source_target_creator (source_skill_id, target_skill_id, creator),
  KEY idx_creator (creator),
  KEY idx_target (target_skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: 写 SkillReference 实体**

`src/main/java/com/agentscopea2a/entity/SkillReference.java`:
```java
package com.agentscopea2a.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillReference {
    private Long id;
    private Long sourceSkillId;
    private Long targetSkillId;
    private String creator;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: 写 SkillReferenceMapper 接口**

`src/main/java/com/agentscopea2a/mapper/mysql/SkillReferenceMapper.java`:
```java
package com.agentscopea2a.mapper.mysql;

import com.agentscopea2a.entity.SkillReference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

@Mapper
public interface SkillReferenceMapper {

    int insert(SkillReference ref);

    int deleteByCreatorTarget(@Param("creator") String creator, @Param("skillId") Long skillId);

    boolean existsByCreatorTarget(@Param("creator") String creator, @Param("skillId") Long skillId);

    /** 当前用户引用过的 skillId 列表(我使用的)。 */
    List<Long> selectSkillIdsByCreator(@Param("creator") String creator);

    /** 当前用户在给定集合中已引用的 skillId(列表行 used 标记批量计算)。 */
    Set<Long> selectUsedSkillIds(@Param("creator") String creator, @Param("ids") List<Long> ids);
}
```

- [ ] **Step 4: 写 SkillReferenceMapper.xml**

`src/main/resources/mybatis/mapper/mysql/SkillReferenceMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.agentscopea2a.mapper.mysql.SkillReferenceMapper">

    <insert id="insert" parameterType="com.agentscopea2a.entity.SkillReference"
            useGeneratedKeys="true" keyProperty="id">
        INSERT INTO skill_reference (source_skill_id, target_skill_id, creator, created_at)
        VALUES (#{sourceSkillId}, #{targetSkillId}, #{creator}, #{createdAt})
    </insert>

    <delete id="deleteByCreatorTarget">
        DELETE FROM skill_reference WHERE creator = #{creator} AND target_skill_id = #{skillId}
    </delete>

    <select id="existsByCreatorTarget" resultType="boolean">
        SELECT COUNT(1) > 0 FROM skill_reference WHERE creator = #{creator} AND target_skill_id = #{skillId}
    </select>

    <select id="selectSkillIdsByCreator" resultType="java.lang.Long">
        SELECT target_skill_id FROM skill_reference WHERE creator = #{creator}
    </select>

    <select id="selectUsedSkillIds" resultType="java.lang.Long">
        SELECT target_skill_id FROM skill_reference
        WHERE creator = #{creator}
        AND target_skill_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
    </select>

</mapper>
```

- [ ] **Step 5: 写失败测试(SkillReferenceService)**

`src/test/java/com/agentscopea2a/v2/service/SkillReferenceServiceTest.java`:
```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.mapper.mysql.SkillReferenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillReferenceServiceTest {

    private SkillReferenceMapper refMapper;
    private SkillService skillService;
    private SkillReferenceService service;

    @BeforeEach
    void setUp() {
        refMapper = mock(SkillReferenceMapper.class);
        skillService = mock(SkillService.class);
        service = new SkillReferenceService(refMapper, skillService);
    }

    @Test
    void reference_inserts_when_not_referenced() {
        when(skillService.get(7L)).thenReturn(Skill.builder().id(7L).status("ACTIVE").build());
        when(refMapper.existsByCreatorTarget("u1", 7L)).thenReturn(false);

        service.reference(7L, "u1");

        verify(refMapper).insert(any());
    }

    @Test
    void reference_is_idempotent_when_already_referenced() {
        when(skillService.get(7L)).thenReturn(Skill.builder().id(7L).status("ACTIVE").build());
        when(refMapper.existsByCreatorTarget("u1", 7L)).thenReturn(true);

        service.reference(7L, "u1");

        verify(refMapper, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void reference_catches_duplicate_key_race() {
        when(skillService.get(7L)).thenReturn(Skill.builder().id(7L).status("ACTIVE").build());
        when(refMapper.existsByCreatorTarget("u1", 7L)).thenReturn(false);
        when(refMapper.insert(any())).thenThrow(new DuplicateKeyException("uk"));

        service.reference(7L, "u1"); // should not throw
    }

    @Test
    void listMine_returns_referenced_skill_ids() {
        when(refMapper.selectSkillIdsByCreator("u1")).thenReturn(List.of(7L, 8L));
        assertThat(service.listMine("u1")).containsExactly(7L, 8L);
    }
}
```

- [ ] **Step 6: 运行测试验证失败**

Run: `mvn -q test -Dtest=SkillReferenceServiceTest`
Expected: 编译失败(`SkillReferenceService` 未创建)。

- [ ] **Step 7: 写 SkillReferenceService 实现**

`src/main/java/com/agentscopea2a/v2/service/SkillReferenceService.java`:
```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.entity.SkillReference;
import com.agentscopea2a.mapper.mysql.SkillReferenceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SkillReferenceService {

    private static final Logger log = LoggerFactory.getLogger(SkillReferenceService.class);

    private final SkillReferenceMapper refMapper;
    private final SkillService skillService;

    public SkillReferenceService(SkillReferenceMapper refMapper, SkillService skillService) {
        this.refMapper = refMapper;
        this.skillService = skillService;
    }

    @Transactional
    public void reference(Long skillId, String userId) {
        Skill s = skillService.get(skillId); // exists check
        if (refMapper.existsByCreatorTarget(userId, skillId)) {
            return; // idempotent
        }
        try {
            refMapper.insert(SkillReference.builder()
                    .sourceSkillId(skillId).targetSkillId(skillId).creator(userId)
                    .createdAt(LocalDateTime.now()).build());
        } catch (DuplicateKeyException e) {
            log.debug("concurrent reference race, idempotent: skill={} user={}", skillId, userId);
        }
    }

    @Transactional
    public void unreference(Long skillId, String userId) {
        refMapper.deleteByCreatorTarget(userId, skillId);
    }

    public List<Long> listMine(String userId) {
        return refMapper.selectSkillIdsByCreator(userId);
    }
}
```

- [ ] **Step 8: 运行测试验证通过**

Run: `mvn -q test -Dtest=SkillReferenceServiceTest`
Expected: PASS。

- [ ] **Step 9: 写 SkillReferenceController**

`src/main/java/com/agentscopea2a/v2/controller/SkillReferenceController.java`:
```java
package com.agentscopea2a.v2.controller;

import com.agentscopea2a.v2.service.SkillReferenceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
@CrossOrigin(origins = "*", maxAge = 3600)
public class SkillReferenceController {

    private final SkillReferenceService referenceService;

    public SkillReferenceController(SkillReferenceService referenceService) {
        this.referenceService = referenceService;
    }

    @PostMapping("/{id}/reference")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reference(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        referenceService.reference(id, userId);
    }

    @DeleteMapping("/{id}/reference")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unreference(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        referenceService.unreference(id, userId);
    }

    @org.springframework.web.bind.annotation.GetMapping("/my-references")
    public List<Long> myReferences(@RequestHeader("X-User-Id") String userId) {
        return referenceService.listMine(userId);
    }
}
```

- [ ] **Step 10: 检查点(不提交)**

Run: `mvn -q test -Dtest=SkillReferenceServiceTest,SkillLikeServiceTest,SkillServiceTest`
Expected: PASS。暂停,由用户决定是否提交。

---

### Task 4: 列表 API(视图/排序/筛选/分页 + liked/used/rank 批量标记)

**Files:**
- Create: `src/main/java/com/agentscopea2a/dto/SkillListQuery.java`
- Create: `src/main/java/com/agentscopea2a/dto/SkillListItem.java`
- Modify: `src/main/java/com/agentscopea2a/mapper/mysql/SkillManageMapper.java`(加 `selectList`)
- Modify: `src/main/resources/mybatis/mapper/mysql/SkillManageMapper.xml`(加 `selectList` 动态 SQL)
- Modify: `src/main/java/com/agentscopea2a/v2/service/SkillService.java`(加 `list`)
- Modify: `src/main/java/com/agentscopea2a/v2/controller/SkillController.java`(加 `GET /api/skills`)
- Test: `src/test/java/com/agentscopea2a/v2/service/SkillServiceListTest.java`

**Interfaces:**
- Consumes: `SkillLikeMapper.selectLikedSkillIds`、`SkillReferenceMapper.selectUsedSkillIds`(Task 2/3)
- Produces: `SkillService.list(query, userId)->List<SkillListItem>`;`SkillListQuery(view/sort/category/tag/keyword/limit/offset)`;`SkillListItem(id/name/description/category/tags/ownerUserId/likeCount/liked/used/available/rank/updatedAt)`;`GET /api/skills?view=&sort=&category=&tag=&keyword=&limit=&offset=`。

- [ ] **Step 1: 写 SkillListQuery 与 SkillListItem DTO**

`src/main/java/com/agentscopea2a/dto/SkillListQuery.java`:
```java
package com.agentscopea2a.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillListQuery {
    private String view;      // all|used|liked|created|popular
    private String sort;      // likes|updated|name
    private String category;
    private String tag;
    private String keyword;
    private Integer limit;
    private Integer offset;
    private String userId;    // 用于 used/liked/created 视图与 liked/used 批量标记

    public String effectiveView() { return view == null ? "all" : view; }
    public String effectiveSort() { return sort == null ? "likes" : sort; }
    public int effectiveLimit() { return limit == null ? 20 : Math.min(limit, 100); }
    public int effectiveOffset() { return offset == null ? 0 : Math.max(offset, 0); }
}
```

`src/main/java/com/agentscopea2a/dto/SkillListItem.java`:
```java
package com.agentscopea2a.dto;

import com.agentscopea2a.entity.Skill;

import java.time.LocalDateTime;

public record SkillListItem(
        Long id, String name, String description, String category, String tags,
        String ownerUserId, long likeCount, boolean liked, boolean used,
        boolean available, Integer rank, LocalDateTime updatedAt
) {
    public static SkillListItem of(Skill s, boolean liked, boolean used, Integer rank) {
        return new SkillListItem(
                s.getId(), s.getName(), s.getDescription(), s.getCategory(), s.getTags(),
                s.getOwnerUserId(),
                s.getLikeCount() == null ? 0L : s.getLikeCount(),
                liked, used, true, rank, s.getUpdatedAt());
    }
}
```
注:`available` 恒为 `true`(本计划不实现可用性;后续计划接入)。

- [ ] **Step 2: 给 SkillManageMapper 加 selectList**

在 `SkillManageMapper.java` 接口末尾(`selectByIds` 后)加:
```java
    java.util.List<Skill> selectList(com.agentscopea2a.dto.SkillListQuery q);
```
(或在文件顶部 import 对应类型;为减少改动,用全限定名。)

- [ ] **Step 3: 写 selectList 动态 SQL(加到 SkillManageMapper.xml 的 `</mapper>` 之前)**

```xml
    <select id="selectList" parameterType="com.agentscopea2a.dto.SkillListQuery" resultMap="BaseResultMap">
        SELECT s.id, s.name, s.description, s.content, s.category, s.tags,
               s.owner_user_id, s.status, s.like_count, s.created_at, s.updated_at, s.deleted_at
        FROM skill_manage s
        <choose>
            <when test="view == 'used'">
                INNER JOIN skill_reference r ON r.target_skill_id = s.id AND r.creator = #{userId}
            </when>
            <when test="view == 'liked'">
                INNER JOIN skill_like l ON l.skill_id = s.id AND l.user_id = #{userId}
            </when>
        </choose>
        <where>
            <choose>
                <when test="view == 'created'">
                    s.owner_user_id = #{userId} AND s.status = 'ACTIVE'
                </when>
                <otherwise>
                    s.status = 'ACTIVE'
                </otherwise>
            </choose>
            <if test="category != null and category != ''">
                AND s.category = #{category}
            </if>
            <if test="tag != null and tag != ''">
                AND FIND_IN_SET(#{tag}, s.tags) &gt; 0
            </if>
            <if test="keyword != null and keyword != ''">
                AND (s.name LIKE CONCAT('%', #{keyword}, '%')
                  OR s.description LIKE CONCAT('%', #{keyword}, '%'))
            </if>
        </where>
        <choose>
            <when test="sort == 'updated'">
                ORDER BY s.updated_at DESC
            </when>
            <when test="sort == 'name'">
                ORDER BY s.name ASC
            </when>
            <otherwise>
                ORDER BY s.like_count DESC, s.updated_at DESC
            </otherwise>
        </choose>
        LIMIT #{effectiveLimit} OFFSET #{effectiveOffset}
    </select>
```
注:`tags` 为逗号分隔字符串,用 `FIND_IN_SET` 过滤;`FIND_IN_SET` 对 `#sql` 形式标签需保证入库时无 `#` 前缀或前后端约定(本计划:tags 存纯 tag,`#` 仅前端展示)。

- [ ] **Step 4: 写失败测试(SkillService.list 批量标记)**

`src/test/java/com/agentscopea2a/v2/service/SkillServiceListTest.java`:
```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.dto.SkillListItem;
import com.agentscopea2a.dto.SkillListQuery;
import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.mapper.mysql.SkillLikeMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import com.agentscopea2a.mapper.mysql.SkillReferenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillServiceListTest {

    private SkillManageMapper manageMapper;
    private SkillLikeMapper likeMapper;
    private SkillReferenceMapper refMapper;
    private SkillService service;

    @BeforeEach
    void setUp() {
        manageMapper = mock(SkillManageMapper.class);
        likeMapper = mock(SkillLikeMapper.class);
        refMapper = mock(SkillReferenceMapper.class);
        service = new SkillService(manageMapper, likeMapper, refMapper);
    }

    @Test
    void list_popular_assigns_rank_and_marks_liked_used() {
        Skill a = Skill.builder().id(1L).name("A").likeCount(120L).status("ACTIVE").build();
        Skill b = Skill.builder().id(2L).name("B").likeCount(80L).status("ACTIVE").build();
        when(manageMapper.selectList(any())).thenReturn(List.of(a, b));
        when(likeMapper.selectLikedSkillIds("u1", List.of(1L, 2L))).thenReturn(Set.of(1L));
        when(refMapper.selectUsedSkillIds("u1", List.of(1L, 2L))).thenReturn(Set.of(2L));

        SkillListQuery q = new SkillListQuery("popular", "likes", null, null, null, 20, 0, "u1");
        List<SkillListItem> items = service.list(q);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).rank()).isEqualTo(1);
        assertThat(items.get(0).liked()).isTrue();
        assertThat(items.get(0).used()).isFalse();
        assertThat(items.get(1).rank()).isEqualTo(2);
        assertThat(items.get(1).liked()).isFalse();
        assertThat(items.get(1).used()).isTrue();
        assertThat(items.get(0).available()).isTrue(); // 本计划恒可用
    }

    @Test
    void list_empty_returns_empty() {
        when(manageMapper.selectList(any())).thenReturn(List.of());
        SkillListQuery q = new SkillListQuery("all", "likes", null, null, null, 20, 0, "u1");
        assertThat(service.list(q)).isEmpty();
    }

    private static <T> T any() { return org.mockito.ArgumentMatchers.any(); }
}
```

- [ ] **Step 5: 运行测试验证失败**

Run: `mvn -q test -Dtest=SkillServiceListTest`
Expected: 编译失败(`SkillService` 构造与 `list` 方法未更新)。

- [ ] **Step 6: 更新 SkillService 构造与 list 方法**

修改 `src/main/java/com/agentscopea2a/v2/service/SkillService.java`:
- 构造器增加 `SkillLikeMapper`、`SkillReferenceMapper` 注入字段。
- 新增 `list` 方法。
完整新文件:
```java
package com.agentscopea2a.v2.service;

import com.agentscopea2a.dto.SkillListItem;
import com.agentscopea2a.dto.SkillListQuery;
import com.agentscopea2a.entity.Skill;
import com.agentscopea2a.mapper.mysql.SkillLikeMapper;
import com.agentscopea2a.mapper.mysql.SkillManageMapper;
import com.agentscopea2a.mapper.mysql.SkillReferenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class SkillService {

    private final SkillManageMapper skillManageMapper;
    private final SkillLikeMapper likeMapper;
    private final SkillReferenceMapper refMapper;

    public SkillService(SkillManageMapper skillManageMapper,
                        SkillLikeMapper likeMapper,
                        SkillReferenceMapper refMapper) {
        this.skillManageMapper = skillManageMapper;
        this.likeMapper = likeMapper;
        this.refMapper = refMapper;
    }

    public List<SkillListItem> list(SkillListQuery q) {
        List<Skill> skills = skillManageMapper.selectList(q);
        if (skills.isEmpty()) {
            return List.of();
        }
        List<Long> ids = skills.stream().map(Skill::getId).toList();
        Set<Long> likedIds = nullToEmpty(likeMapper.selectLikedSkillIds(q.getUserId(), ids));
        Set<Long> usedIds = nullToEmpty(refMapper.selectUsedSkillIds(q.getUserId(), ids));
        boolean rankVisible = "popular".equals(q.effectiveView());
        int rank = q.effectiveOffset() + 1;
        List<SkillListItem> items = new ArrayList<>(skills.size());
        for (Skill s : skills) {
            items.add(SkillListItem.of(s, likedIds.contains(s.getId()),
                    usedIds.contains(s.getId()), rankVisible ? rank : null));
            rank++;
        }
        return items;
    }

    private static Set<Long> nullToEmpty(Set<Long> set) {
        return set == null ? Set.of() : set;
    }

    @Transactional
    public Skill create(Skill skill, String ownerUserId) {
        if (skillManageMapper.existsByName(skill.getName())) {
            throw new IllegalStateException("SkillNameConflict: " + skill.getName());
        }
        skill.setOwnerUserId(ownerUserId);
        skill.setStatus("ACTIVE");
        skill.setLikeCount(0L);
        skill.setCreatedAt(LocalDateTime.now());
        skill.setUpdatedAt(LocalDateTime.now());
        skillManageMapper.insert(skill);
        return skillManageMapper.selectById(skill.getId());
    }

    public Skill get(Long id) {
        Skill s = skillManageMapper.selectById(id);
        if (s == null || "DELETED".equals(s.getStatus())) {
            throw new IllegalStateException("SkillNotFound: " + id);
        }
        return s;
    }

    @Transactional
    public Skill update(Long id, Skill patch, String userId) {
        Skill s = get(id);
        if (!s.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("SkillAccessDenied: " + id);
        }
        if (patch.getName() != null && !patch.getName().equals(s.getName())
                && skillManageMapper.existsByName(patch.getName())) {
            throw new IllegalStateException("SkillNameConflict: " + patch.getName());
        }
        if (patch.getName() != null) s.setName(patch.getName());
        if (patch.getDescription() != null) s.setDescription(patch.getDescription());
        if (patch.getContent() != null) s.setContent(patch.getContent());
        if (patch.getCategory() != null) s.setCategory(patch.getCategory());
        if (patch.getTags() != null) s.setTags(patch.getTags());
        s.setUpdatedAt(LocalDateTime.now());
        skillManageMapper.update(s);
        return skillManageMapper.selectById(id);
    }

    @Transactional
    public void delete(Long id, String userId) {
        Skill s = get(id);
        if (!s.getOwnerUserId().equals(userId)) {
            throw new IllegalStateException("SkillAccessDenied: " + id);
        }
        skillManageMapper.softDelete(id);
    }
}
```
注意:`SkillServiceTest`(Task 1)构造器签名变了——更新其 `setUp()` 为 `new SkillService(mapper, mock(SkillLikeMapper.class), mock(SkillReferenceMapper.class))`。

- [ ] **Step 7: 更新 Task 1 的 SkillServiceTest 构造**

修改 `src/test/java/com/agentscopea2a/v2/service/SkillServiceTest.java` 的 `setUp()`:
```java
    @BeforeEach
    void setUp() {
        mapper = mock(SkillManageMapper.class);
        service = new SkillService(mapper,
                mock(com.agentscopea2a.mapper.mysql.SkillLikeMapper.class),
                mock(com.agentscopea2a.mapper.mysql.SkillReferenceMapper.class));
    }
```

- [ ] **Step 8: 给 SkillController 加 GET /api/skills**

在 `SkillController.java` 加(并补 import):
```java
    @GetMapping
    public List<com.agentscopea2a.dto.SkillListItem> list(
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestHeader("X-User-Id") String userId) {
        return skillService.list(new com.agentscopea2a.dto.SkillListQuery(
                view, sort, category, tag, keyword, limit, offset, userId));
    }
```
并 import `org.springframework.web.bind.annotation.RequestParam` 与 `java.util.List`。

- [ ] **Step 9: 运行测试验证通过**

Run: `mvn -q test -Dtest=SkillServiceListTest,SkillServiceTest,SkillLikeServiceTest,SkillReferenceServiceTest`
Expected: PASS。

- [ ] **Step 10: 全量编译 + 启动冒烟**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。
启动应用(用户手动或 CI):确认 Flyway 迁移 3 张表建成功、`POST /api/skills` 建一条、`POST /api/skills/{id}/like` 两次(第二次计数不变)、`GET /api/skills?view=all&sort=likes` 返回按点赞倒序。

- [ ] **Step 11: 检查点(不提交)**

Run: `mvn -q test`
Expected: 全绿。暂停,由用户决定是否提交。

### Task 5: 前端基础(types + api/skill.ts + SkillShell 布局 + vite 代理)

**Files:**
- Create: `frontend/src/types/skill.ts`
- Create: `frontend/src/api/skill.ts`
- Create: `frontend/src/components/SkillShell.vue`
- Modify: `frontend/vite.config.ts`(加 `/api` 代理)
- (本任务不改 `main.ts`,组件先不接线,保证构建绿色;Task 6 接线)

**Interfaces:**
- Consumes: 后端 `/api/skills...`(Task 1-4)
- Produces: `api/skill.ts`(listSkills/getSkill/likeSkill/unlikeSkill/getLikeStatus/referenceSkill/unreferenceSkill);`SkillShell.vue`(左侧导航布局);`types/skill.ts`(`SkillListItem` / `SkillDetail` / `LikeStatus`)。

- [ ] **Step 1: 写 types/skill.ts**

`frontend/src/types/skill.ts`:
```ts
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
```

- [ ] **Step 2: 写 api/skill.ts**

`frontend/src/api/skill.ts`:
```ts
import type { SkillListItem, SkillDetail, LikeStatus } from '../types/skill';

const BASE = '/api/skills';

/** 临时用户标识:本工程无鉴权,从 localStorage 取,默认 demo-user。 */
function authHeaders(): Record<string, string> {
  return { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' };
}

export interface SkillListParams {
  view?: 'all' | 'used' | 'liked' | 'created' | 'popular';
  sort?: 'likes' | 'updated' | 'name';
  category?: string;
  tag?: string;
  keyword?: string;
  limit?: number;
  offset?: number;
}

export async function listSkills(params: SkillListParams): Promise<SkillListItem[]> {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v != null && v !== '') qs.set(k, String(v));
  }
  const res = await fetch(`${BASE}?${qs.toString()}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`listSkills failed: ${res.status}`);
  return res.json();
}

export async function getSkill(id: number): Promise<SkillDetail> {
  const res = await fetch(`${BASE}/${id}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getSkill failed: ${res.status}`);
  return res.json();
}

export async function likeSkill(id: number): Promise<LikeStatus> {
  const res = await fetch(`${BASE}/${id}/like`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw new Error(`like failed: ${res.status}`);
  return res.json();
}

export async function unlikeSkill(id: number): Promise<LikeStatus> {
  const res = await fetch(`${BASE}/${id}/like`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error(`unlike failed: ${res.status}`);
  return res.json();
}

export async function getLikeStatus(id: number): Promise<LikeStatus> {
  const res = await fetch(`${BASE}/${id}/like`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`getLikeStatus failed: ${res.status}`);
  return res.json();
}

export async function referenceSkill(id: number): Promise<void> {
  const res = await fetch(`${BASE}/${id}/reference`, { method: 'POST', headers: authHeaders() });
  if (!res.ok) throw new Error(`reference failed: ${res.status}`);
}

export async function unreferenceSkill(id: number): Promise<void> {
  const res = await fetch(`${BASE}/${id}/reference`, { method: 'DELETE', headers: authHeaders() });
  if (!res.ok) throw new Error(`unreference failed: ${res.status}`);
}
```

- [ ] **Step 3: 写 SkillShell.vue(左侧导航布局)**

`frontend/src/components/SkillShell.vue`:
```vue
<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router';

const nav = [
  { to: '/skills', label: '全部 Skill' },
  { to: '/skills/used', label: '我使用的' },
  { to: '/skills/liked', label: '我点赞的' },
  { to: '/skills/created', label: '我创建的' },
  { to: '/skills/popular', label: '热门榜' },
  { to: '/skills/category', label: '分类浏览' },
];
</script>

<template>
  <div class="skill-shell">
    <aside class="nav">
      <div class="logo">Skill 广场</div>
      <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="nav-item">{{ n.label }}</RouterLink>
    </aside>
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.skill-shell { display: flex; min-height: 100vh; }
.nav { width: 200px; background: #0f172a; color: #cbd5e1; padding: 12px; display: flex; flex-direction: column; gap: 4px; }
.logo { font-weight: bold; color: #fff; margin-bottom: 12px; }
.nav-item { padding: 8px 10px; border-radius: 6px; text-decoration: none; color: #cbd5e1; }
.nav-item.router-link-active { background: #3b82f6; color: #fff; }
.content { flex: 1; padding: 16px; background: #f1f5f9; }
</style>
```

- [ ] **Step 4: vite.config.ts 加 /api 代理**

修改 `frontend/vite.config.ts` 的 `server.proxy`,在 `/ai` 后加 `/api`:
```ts
  server: {
    port: 5173,
    proxy: {
      '/v2': { target: 'http://localhost:8081', changeOrigin: true },
      '/ai': { target: 'http://localhost:8081', changeOrigin: true },
      '/api': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
```

- [ ] **Step 5: 构建验证(新文件可编译,未接线不影响)**

Run: `cd frontend && npm run build`
Expected: 构建成功(`vite build` 产出 `../src/main/resources/static`)。

- [ ] **Step 6: 检查点(不提交)**

暂停,由用户决定是否提交。

---

### Task 6: 卡片组件 + 列表组件 + 全部视图 + 点赞交互

**Files:**
- Create: `frontend/src/components/SkillCard.vue`
- Create: `frontend/src/components/SkillRow.vue`
- Create: `frontend/src/components/SkillList.vue`(网格/列表切换 + 点赞乐观更新)
- Create: `frontend/src/pages/skill/SkillListPage.vue`(通用列表页,按 `view` prop 复用)
- Modify: `frontend/src/main.ts`(加 `/skills` 路由,先挂全部视图)

**Interfaces:**
- Consumes: `api/skill.ts`(listSkills/likeSkill/unlikeSkill)
- Produces: `SkillCard`/`SkillRow`(props: item[,rank];emit `like`);`SkillList`(props: items[,showRank];内部管密度+点赞);`SkillListPage`(props: view/showRank/allowCategory)。

- [ ] **Step 1: 写 SkillCard.vue**

`frontend/src/components/SkillCard.vue`:
```vue
<script setup lang="ts">
import type { SkillListItem } from '../types/skill';
defineProps<{ item: SkillListItem }>();
defineEmits<{ (e: 'like'): void }>();
</script>

<template>
  <div class="card">
    <div class="top">
      <span class="badge" :class="item.available ? 'ok' : 'no'">{{ item.available ? '🟢 可用' : '⚪ 不可用' }}</span>
      <span class="count">👍 {{ item.likeCount }}</span>
    </div>
    <div class="name">{{ item.name }}</div>
    <div class="desc">{{ item.description }}</div>
    <div class="meta">{{ item.ownerUserId }} · {{ item.category || '未分类' }}</div>
    <div class="tags">
      <span v-for="t in (item.tags || '').split(',').filter(Boolean)" :key="t" class="tag">#{{ t }}</span>
      <span v-if="item.used" class="used">已使用</span>
    </div>
    <div class="actions">
      <button class="like" :class="{ on: item.liked }" @click="$emit('like')">
        👍 {{ item.liked ? '已点赞' : '点赞' }}
      </button>
      <RouterLink :to="`/skills/${item.id}`" class="detail">详情</RouterLink>
    </div>
  </div>
</template>

<style scoped>
.card { background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px; display: flex; flex-direction: column; gap: 6px; }
.top { display: flex; justify-content: space-between; }
.badge.ok { color: #16a34a; } .badge.no { color: #94a3b8; }
.count { color: #db2777; }
.name { font-weight: bold; font-size: 15px; }
.desc { color: #64748b; font-size: 13px; min-height: 18px; }
.meta { color: #94a3b8; font-size: 12px; }
.tags { display: flex; gap: 4px; flex-wrap: wrap; }
.tag { background: #f1f5f9; padding: 0 6px; border-radius: 4px; font-size: 11px; color: #475569; }
.used { background: #e0f2fe; color: #0284c7; padding: 0 6px; border-radius: 4px; font-size: 11px; }
.actions { display: flex; gap: 8px; align-items: center; margin-top: 4px; }
.like { padding: 4px 12px; border-radius: 14px; border: 1px solid #cbd5e1; background: #fff; cursor: pointer; }
.like.on { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.detail { font-size: 12px; color: #2563eb; text-decoration: none; }
</style>
```

- [ ] **Step 2: 写 SkillRow.vue**

`frontend/src/components/SkillRow.vue`:
```vue
<script setup lang="ts">
import type { SkillListItem } from '../types/skill';
defineProps<{ item: SkillListItem; rank: number | null }>();
defineEmits<{ (e: 'like'): void }>();
</script>

<template>
  <div class="row">
    <span v-if="rank !== null" class="rank">#{{ rank }}</span>
    <div class="main">
      <div class="line1">
        <span class="name">{{ item.name }}</span>
        <span class="badge" :class="item.available ? 'ok' : 'no'">{{ item.available ? '🟢可用' : '⚪不可用' }}</span>
        <span v-if="item.used" class="used">已使用</span>
      </div>
      <div class="line2">
        {{ item.description }} · {{ item.ownerUserId }} · {{ item.category || '未分类' }}
        · {{ (item.tags || '').split(',').filter(Boolean).map(t => '#' + t).join(' ') }}
      </div>
    </div>
    <div class="right">
      <span class="count">👍 {{ item.likeCount }}</span>
      <button class="like" :class="{ on: item.liked }" @click="$emit('like')">👍</button>
    </div>
  </div>
</template>

<style scoped>
.row { display: flex; align-items: center; gap: 12px; background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 12px; }
.rank { color: #f59e0b; font-weight: bold; width: 36px; }
.main { flex: 1; }
.line1 { display: flex; align-items: center; gap: 8px; }
.name { font-weight: bold; }
.badge.ok { color: #16a34a; } .badge.no { color: #94a3b8; } .used { color: #0284c7; font-size: 11px; }
.line2 { color: #94a3b8; font-size: 12px; margin-top: 2px; }
.right { display: flex; align-items: center; gap: 8px; }
.count { color: #db2777; }
.like { border: 1px solid #cbd5e1; background: #fff; border-radius: 14px; cursor: pointer; padding: 2px 10px; }
.like.on { background: #3b82f6; color: #fff; border-color: #3b82f6; }
</style>
```

- [ ] **Step 3: 写 SkillList.vue(密度切换 + 点赞乐观更新)**

`frontend/src/components/SkillList.vue`:
```vue
<script setup lang="ts">
import { ref, watch } from 'vue';
import type { SkillListItem } from '../types/skill';
import SkillCard from './SkillCard.vue';
import SkillRow from './SkillRow.vue';
import { likeSkill, unlikeSkill } from '../api/skill';

const props = defineProps<{ items: SkillListItem[]; showRank?: boolean }>();

const density = ref<'grid' | 'list'>(
  (localStorage.getItem('skill-density') as 'grid' | 'list') || 'grid');
watch(density, (d) => localStorage.setItem('skill-density', d));

async function toggleLike(item: SkillListItem) {
  const before = { liked: item.liked, likeCount: item.likeCount };
  item.liked = !item.liked;
  item.likeCount += item.liked ? 1 : -1; // 乐观更新
  try {
    const status = item.liked ? await likeSkill(item.id) : await unlikeSkill(item.id);
    item.likeCount = status.likeCount;
    item.liked = status.liked;
  } catch {
    item.liked = before.liked; // 回滚
    item.likeCount = before.likeCount;
  }
}

function rankOf(it: SkillListItem, i: number): number | null {
  return props.showRank ? (it.rank ?? i + 1) : null;
}
</script>

<template>
  <div class="toolbar">
    <button :class="{ on: density === 'grid' }" @click="density = 'grid'">▦ 网格</button>
    <button :class="{ on: density === 'list' }" @click="density = 'list'">≡ 列表</button>
  </div>
  <div v-if="items.length === 0" class="empty">暂无 Skill</div>
  <div v-else-if="density === 'grid'" class="grid">
    <SkillCard v-for="it in items" :key="it.id" :item="it" @like="toggleLike(it)" />
  </div>
  <div v-else class="list">
    <SkillRow v-for="(it, i) in items" :key="it.id" :item="it" :rank="rankOf(it, i)" @like="toggleLike(it)" />
  </div>
</template>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 6px; }
button { padding: 4px 10px; border: 1px solid #94a3b8; background: #fff; border-radius: 6px; cursor: pointer; }
button.on { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
.list { display: flex; flex-direction: column; gap: 8px; }
.empty { color: #64748b; padding: 24px; text-align: center; }
</style>
```

- [ ] **Step 4: 写 SkillListPage.vue(通用列表页)**

`frontend/src/pages/skill/SkillListPage.vue`:
```vue
<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import SkillList from '../../components/SkillList.vue';
import { listSkills } from '../../api/skill';
import type { SkillListItem } from '../../types/skill';

const props = withDefaults(defineProps<{
  view: 'all' | 'used' | 'liked' | 'created' | 'popular';
  showRank?: boolean;
  allowCategory?: boolean;
}>(), { showRank: false, allowCategory: false });

const items = ref<SkillListItem[]>([]);
const sort = ref<'likes' | 'updated' | 'name'>('likes');
const category = ref('');
const keyword = ref('');
const categories = ['数据', '办公', '研发', '业务'];

const title = computed(() => ({
  all: '全部 Skill', used: '我使用的 Skill', liked: '我点赞的 Skill',
  created: '我创建的 Skill', popular: '热门榜',
}[props.view]));

async function load() {
  items.value = await listSkills({
    view: props.view, sort: sort.value,
    category: category.value || undefined,
    keyword: keyword.value || undefined,
  });
}
watch([sort, category, () => props.view], load, { immediate: true });
</script>

<template>
  <h2>{{ title }}</h2>
  <div class="bar">
    <input v-model="keyword" placeholder="搜索 skill" @keyup.enter="load" />
    <select v-if="allowCategory" v-model="category">
      <option value="">全部分类</option>
      <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
    </select>
    <select v-model="sort">
      <option value="likes">点赞最多</option>
      <option value="updated">最新更新</option>
      <option value="name">名称</option>
    </select>
  </div>
  <SkillList :items="items" :show-rank="showRank" />
</template>

<style scoped>
.bar { display: flex; gap: 8px; margin-bottom: 8px; }
input, select { padding: 4px 8px; border: 1px solid #cbd5e1; border-radius: 6px; }
h2 { margin: 0 0 8px; }
</style>
```

- [ ] **Step 5: main.ts 加 /skills 路由(先挂全部视图)**

修改 `frontend/src/main.ts`:在 import 区加:
```ts
import SkillShell from './components/SkillShell.vue';
import SkillListPage from './pages/skill/SkillListPage.vue';
```
在 `routes` 数组里、`{ path: '/:pathMatch(.*)*'` 之前加:
```ts
  {
    path: '/skills',
    component: SkillShell,
    children: [
      { path: '', component: SkillListPage, props: { view: 'all' } },
    ],
  },
```

- [ ] **Step 6: 构建验证**

Run: `cd frontend && npm run build`
Expected: 构建成功。

- [ ] **Step 7: 人工验证(全部视图 + 点赞)**

后端启动(8081)后:
Run: `cd frontend && npm run dev`
打开 `http://localhost:5173/skills`:
Expected: 左侧导航 + "全部 Skill"标题 + 排序/搜索条 + Skill 卡片网格;点"网格/列表"切换;点"👍 点赞"按钮乐观 +1 且高亮,刷新后保持(走 `/api/skills/{id}/like`)。

- [ ] **Step 8: 检查点(不提交)**

暂停,由用户决定是否提交。

---

### Task 7: 其余视图路由 + Skill 详情页

**Files:**
- Modify: `frontend/src/main.ts`(加 used/liked/created/popular/category/:id 路由)
- Create: `frontend/src/pages/skill/SkillDetailPage.vue`

**Interfaces:**
- Consumes: `SkillListPage`(Task 6)、`api/skill.ts`(getSkill/getLikeStatus/likeSkill/unlikeSkill/referenceSkill)
- Produces: 完整 6 视图 + 详情页(点赞/引用/内容展示)。

- [ ] **Step 1: main.ts 补齐路由**

修改 `frontend/src/main.ts` 的 `/skills` children,替换为:
```ts
  {
    path: '/skills',
    component: SkillShell,
    children: [
      { path: '', component: SkillListPage, props: { view: 'all', allowCategory: true } },
      { path: 'used', component: SkillListPage, props: { view: 'used' } },
      { path: 'liked', component: SkillListPage, props: { view: 'liked' } },
      { path: 'created', component: SkillListPage, props: { view: 'created' } },
      { path: 'popular', component: SkillListPage, props: { view: 'popular', showRank: true } },
      { path: 'category', component: SkillListPage, props: { view: 'all', allowCategory: true } },
      { path: ':id', component: SkillDetailPage },
    ],
  },
```
并 import:
```ts
import SkillDetailPage from './pages/skill/SkillDetailPage.vue';
```

- [ ] **Step 2: 写 SkillDetailPage.vue**

`frontend/src/pages/skill/SkillDetailPage.vue`:
```vue
<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { getSkill, getLikeStatus, likeSkill, unlikeSkill, referenceSkill } from '../../api/skill';
import type { SkillDetail, LikeStatus } from '../../types/skill';

const route = useRoute();
const skill = ref<SkillDetail | null>(null);
const like = ref<LikeStatus>({ liked: false, likeCount: 0 });
const referenced = ref(false);

async function load() {
  const id = Number(route.params.id);
  skill.value = await getSkill(id);
  like.value = await getLikeStatus(id);
}
async function toggleLike() {
  if (!skill.value) return;
  like.value = like.value.liked
    ? await unlikeSkill(skill.value.id)
    : await likeSkill(skill.value.id);
}
async function doReference() {
  if (!skill.value) return;
  await referenceSkill(skill.value.id);
  referenced.value = true;
}
watch(() => route.params.id, load, { immediate: true });
</script>

<template>
  <div v-if="skill">
    <h2>{{ skill.name }} <span class="cnt">👍 {{ like.likeCount }}</span></h2>
    <div class="meta">{{ skill.ownerUserId }} · {{ skill.category || '未分类' }} · 状态 {{ skill.status }}</div>
    <div class="actions">
      <button :class="{ on: like.liked }" @click="toggleLike">{{ like.liked ? '👍 已点赞' : '👍 点赞' }}</button>
      <button :disabled="referenced" @click="doReference">{{ referenced ? '已引用' : '引用' }}</button>
    </div>
    <h3>描述</h3>
    <p>{{ skill.description }}</p>
    <h3>内容</h3>
    <pre>{{ skill.content }}</pre>
  </div>
  <div v-else>加载中…</div>
</template>

<style scoped>
.cnt { color: #db2777; font-size: 16px; }
.meta { color: #94a3b8; margin-bottom: 8px; }
.actions { display: flex; gap: 8px; margin-bottom: 12px; }
button { padding: 6px 14px; border-radius: 14px; border: 1px solid #cbd5e1; background: #fff; cursor: pointer; }
button.on { background: #3b82f6; color: #fff; border-color: #3b82f6; }
button:disabled { opacity: 0.6; }
pre { background: #0f172a; color: #e2e8f0; padding: 12px; border-radius: 8px; white-space: pre-wrap; }
</style>
```

- [ ] **Step 3: 构建验证**

Run: `cd frontend && npm run build`
Expected: 构建成功。

- [ ] **Step 4: 人工验证(全视图 + 详情页)**

后端启动后 `npm run dev`,依次访问:
- `/skills`(全部,含分类筛选)、`/skills/popular`(列表+排名 #1 #2)、`/skills/used`(我引用的)、`/skills/liked`、`/skills/created`、`/skills/category`。
- 点卡片"详情"进入 `/skills/:id`:展示 content、点赞按钮、引用按钮。
Expected: 各视图数据与后端 `view` 参数一致;热门榜带排名;详情页点赞/引用可点。

- [ ] **Step 5: 全栈冒烟**

Run: `mvn -q test`(后端)+ `cd frontend && npm run build`(前端)
Expected: 后端全绿、前端构建成功。

- [ ] **Step 6: 检查点(不提交)**

暂停,由用户决定是否提交。计划完成。

---

## Self-Review

**1. Spec coverage:**
- §4.8 点赞模块(规则/排序/幂等)-> Task 2 ✓
- §5.1 skill_manage + like_count + idx_like_rank -> Task 1 ✓
- §5.8 skill_like 表 -> Task 2 ✓
- §5.6 skill_reference(撑"我使用的")-> Task 3 ✓
- §7.1 列表 API(view/sort/filter/分页 + liked/used/rank)-> Task 4 ✓
- §7.6 点赞 API(幂等 toggle)-> Task 2 ✓
- §7.4 引用 API -> Task 3 ✓
- §11.1 左侧导航布局 -> Task 5 ✓
- §11.2 卡片网格/列表 + 可用性徽章/点赞/已使用/排名 -> Task 6 ✓
- §11.3 排序与筛选 -> Task 6/7 ✓
- §11.4 各视图查询逻辑 -> Task 4(后端)+ Task 6/7(前端)✓
- §11.5 交互(乐观更新/密度切换/空状态/详情页)-> Task 6/7 ✓
- §11.6 点赞数据流(事务:insert + 原子 ±1)-> Task 2 ✓
- §11.7 架构(Vue SPA 合并构建 + vite /api 代理 + Flyway + 单 jar)-> Task 1-5 ✓
- 明确延后(不在本计划):`skill_publish`/审批/`skill_user_disable`/可用性计算(§6)/`skill_version_history`/`skill_operation_history`/待我审批视图/可用性徽章真实判定(本计划 `available` 恒 true)-> Global Constraints 已声明 ✓

**2. Placeholder scan:** 无 TBD/TODO/"add error handling"等;每步含完整代码。`IllegalStateException` 作为轻量异常为有意简化(非占位符),已在 Task 1 Step 5 注明后续可替换为 §8 自定义异常。`categories` 为前端固定列表(注明后续可改为 API 获取)。

**3. Type consistency:**
- `SkillListItem` 字段(后端 record 与前端 `types/skill.ts`)对齐:id/name/description/category/tags/ownerUserId/likeCount/liked/used/available/rank/updatedAt ✓
- `LikeStatus`(后端 record `liked,likeCount` <-> 前端 interface)✓
- Mapper 方法名在接口/XML/service/test 间一致:`selectByUserSkill`/`selectLikedSkillIds`/`insert`/`deleteByUserSkill`/`incrementLikeCount`/`decrementLikeCount`/`selectLikeCount`/`selectList`/`selectUsedSkillIds` 等 ✓
- `SkillService` 构造器:Task 1 为 3 参(更新后),Task 4 测试与 Task 1 测试(Step 7 已更新 setUp)一致 ✓
- **已修正 `SkillListQuery`**:原写法用 `record` 并试图重写 `limit()`/`offset()` 返回 `int`,与 record 访问器返回类型冲突(编译错误),且作 MyBatis `parameterType` 时 OGNL 属性解析有不确定性。已改为 Lombok `@Data` 类 + `effectiveView()`/`effectiveSort()`/`effectiveLimit()`/`effectiveOffset()` 方法,XML 的 `LIMIT/OFFSET` 改用 `#{effectiveLimit}`/`#{effectiveOffset}`,`SkillService.list` 改用 `q.effectiveView()`/`q.getUserId()`/`q.effectiveOffset()`。(该修正已在 Task 4 各 Step 落实。)

**4. Scope check:** 单一计划,产出可端到端演示的"点赞 + Skill 广场 6 视图"核心闭环;发布/可用性等明确延后为后续计划。范围合适。

## Execution Handoff

计划已保存到 `docs/superpowers/plans/2026-07-23-skill-like-and-marketplace-frontend.md`。两种执行方式:

**1. Subagent-Driven(推荐)** - 每个 Task 派一个全新 subagent 执行,任务间我审查,迭代快。
**2. Inline Execution** - 在本会话用 executing-plans 批量执行,带检查点审查。

**选哪种?**(注:无论哪种,遵循用户"不提交代码"要求,不执行 git commit/push。)
