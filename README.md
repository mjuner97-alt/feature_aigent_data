# Workspace 目录结构

## 默认路径配置

- **自动加载**: `io.agentscope.harness.agent.hook.WorkspaceContextHook.injectWorkspaceContext`
- **框架常量**:`io.agentscope.harness.agent.workspace.WorkspaceConstants`
- **应用配置**:`harness.a2a.workspace.path=.agentscope/workspace`
- **文件路径** `在resource目录下创建workspace(.agentscope/后面的路径)`
- **skill入口解析**:`io.agentscope.harness.agent.HarnessAgent.Builder.resolveSkillBox`

## 目录结构说明

```text
.agentscope/workspace/          # 工作空间根目录
├── AGENTS.md                   # 工牌 & 员工手册
│                               # 定义 Agent 的身份、行为准则和工作流程
├── MEMORY.md                   # 核心笔记本
│                               # 存放压缩、整理后的长期记忆
├── memory/                     # 每日工作日志
│                               # 记录每天对话的原始流水账
├── skills/                     # 工具箱
│   ├── skill-name-1/
│   │   ├── SKILL.md            # Required:入口文件,包含 YAML frontmatter
│   │   ├── references/         # Optional:参考文档
│   │   ├── examples/           # Optional:示例文件
│   │   └── scripts/            # Optional:脚本文件
│   └── skill-name-2/
│       └── SKILL.md
├── knowledge/                  # 书架 / 知识库
│                               # 存放产品手册、FAQ 等参考资料
└── sessions/                   # 工作记录本
                                # 完整保存每次对话的详细记录
```

## 各文件 / 文件夹作用速查表

| 文件 / 文件夹 | 作用类比 | 核心说明 |
| --- | --- | --- |
| `AGENTS.md` | 工牌 & 员工手册 | 定义 Agent 身份、行为准则和工作流程,作为系统提示注入 |
| `MEMORY.md` | 核心笔记本 | 存放压缩整理的长期记忆 |
| `memory/` | 每日工作日志 | 记录每天对话的原始流水账,定期整理到 `MEMORY.md` |
| `skills/` | 工具箱 | 存放 Agent 可调用的专业技能,每个技能有独立的 `SKILL.md` |
| `knowledge/` | 书架 / 知识库 | 存放产品手册、FAQ 等参考资料 |
| `sessions/` | 工作记录本 | 完整保存每次对话记录,支持服务重启后连续对话 |

## 技能(Skill)目录规范

每个技能文件夹必须包含 `SKILL.md` 入口文件,可选包含:

- `references/` — 参考文档
- `examples/` — 示例文件
- `scripts/` — 脚本文件
