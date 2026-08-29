# 知识动态检索设计（Knowledge Dynamic Retrieval）

## 1. 背景与问题

当前 `WorkspaceContextHook`（JAR 内部，不可修改）会 **无条件注入** `knowledge/` 目录下的所有文件到每次系统提示词。这意味着 `QI_KNOWLEDGE.md`（QI卡口领域术语）即使用户问题完全无关也会被加载，浪费上下文窗口。

项目已有成熟的分层模式：`skills/`（全量）+ `skills-auto/`（按需检索）。本方案将同一模式应用于知识文件。

## 2. 方案设计

### 2.1 目录结构

```
workspace/
├── knowledge/                    # 不变 — 始终加载（由 WorkspaceContextHook 注入）
│   ├── KNOWLEDGE.md              # 通用维度格式规则
│   └── metric-categories.yaml    # 指标分类配置
├── knowledge-dynamic/            # 新增 — 按需加载（由 KnowledgeRetrievalHook 注入）
│   ├── knowledge-index.yaml      # 关键词→文件映射配置
│   └── QI_KNOWLEDGE.md           # 从 knowledge/ 迁移过来
```

### 2.2 knowledge-index.yaml 格式

```yaml
# 将关键词映射到 knowledge-dynamic/ 下的文件
# 关键词按配置顺序检查，首个匹配即命中
# 以 \ 开头的关键词视为正则表达式；其他使用大小写不敏感的 contains() 匹配
entries:
  - file: QI_KNOWLEDGE.md
    keywords:
      - QI卡口
      - Q1卡口
      - Q2卡口
      - Q3卡口
      - Q4卡口
      - 在途
      - 在建
      - 启动中
      - 未投产
    description: QI卡口相关术语和定义
```

### 2.3 核心组件

#### KnowledgeRetrievalHook（新增）

| 属性 | 值 |
|------|-----|
| 包 | `com.agentscopea2a.harness.hooks` |
| 优先级 | `-40`（SkillRetrievalHook -50 与 WorkspaceContextHook ~0 之间） |
| 触发事件 | `PreCallEvent` |
| 匹配方式 | **纯关键词匹配**（同 MetricClassificationService 约定：`\` 前缀 = 正则，否则大小写不敏感 contains） |
| 注入方式 | `event.appendSystemContent()` |
| 注入标记 | `\n\n<!-- knowledge.retrieved -->\n` |
| 内容截断 | 最大 4000 字符 |
| 开关 | `harness.knowledge.retrieval.enabled=true`（默认开启） |
| 异常处理 | 捕获所有异常并日志记录，绝不阻塞请求 |

流程：
```
PreCallEvent → 提取用户问题
             → 遍历 knowledge-index.yaml 的 entries
             → 关键词匹配（纯关键词，无 LLM）
             → 匹配命中 → 读取 knowledge-dynamic/<file>
                        → 截断至 4000 字符
                        → appendSystemContent()
             → 未命中 → 无操作
```

### 2.4 各组件变更

| 组件 | 变更 |
|------|------|
| **WorkspaceMaterializer** | `ALWAYS_OVERWRITE_PREFIXES` 增加 `"knowledge-dynamic/"` |
| **FilesystemConfig** | `workspaceProjectionRoots` 增加 `"knowledge-dynamic"`；`buildSandboxSpec()` 增加 `knowledge-dynamic` bind mount |
| **SupervisorService** | 在 `build()` 方法中 wire `KnowledgeRetrievalHook`；增加 `@Value("${harness.knowledge.retrieval.enabled:true}")` |
| **application.properties** | 增加 `harness.knowledge.retrieval.enabled=true` |
| **QI_KNOWLEDGE.md** | 从 `knowledge/` 移动到 `knowledge-dynamic/` |
| **knowledge-index.yaml** | 新建 |

### 2.5 不变的部分

- `knowledge/KNOWLEDGE.md` 仍在 `knowledge/` 下，始终由 WorkspaceContextHook 全量注入 ✅
- `knowledge/metric-categories.yaml` 仍在 `knowledge/` 下，始终由 MetricClassificationService 读取 ✅
- `SkillRetrievalHook` 不变 ✅
- `FileSystemSkillRepository` 不变 ✅
- `WorkspaceContextHook` 不变 ✅

### 2.6 子智能体访问

| 目录 | 主智能体 | 子智能体（sandbox） |
|------|---------|-------------------|
| `knowledge/` | ✅ 始终（WorkspaceContextHook） | ✅ 始终（workspaceProjectionRoots） |
| `knowledge-dynamic/` | ✅ 关键词命中时（KnowledgeRetrievalHook） | ✅ 文件可见（bind mount + projection） |

子智能体间接获得动态知识：KnowledgeRetrievalHook 将匹配内容追加到主智能体系统提示词，通过 subagent context 传递给子智能体。

## 3. 验证计划

1. **关键词命中测试**：发送含"QI卡口"的问题，验证系统提示词包含 QI_KNOWLEDGE.md 内容
2. **关键词未命中测试**：发送无关问题，验证系统提示词不包含 QI_KNOWLEDGE.md 内容
3. **回归测试**：验证 `knowledge/KNOWLEDGE.md` 仍在每次请求中出现
4. **Sandbox 测试**：验证 `knowledge-dynamic/` 在 Docker 容器内可见
5. **禁用测试**：设置 `harness.knowledge.retrieval.enabled=false`，验证无动态知识注入