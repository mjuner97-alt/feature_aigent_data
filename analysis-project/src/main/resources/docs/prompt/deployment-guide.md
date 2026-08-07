# 部署 / 启动 / 配置 注意文档

> 创建日期：2026/08/06
> 适用版本：analysis-project 2.0.0-RC5 (plan-b 镜像)
> 关联：`Dockerfile`, `entrypoint.sh`, `nginx.conf`, `application-docker.properties`

## 1. 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│ 宿主机 docker-host (116.148.125.236)                         │
│                                                              │
│  /java/analysis-project/analysis-project/   ← 源码 (git)     │
│    ├─ src/                  ← Java + Python 源码             │
│    ├─ frontend-pm/          ← 前端 (Vite + React)            │
│    ├─ target/*.jar          ← prebuilt 后端 jar              │
│    ├─ Dockerfile                                            │
│    ├─ entrypoint.sh                                         │
│    └─ nginx.conf                                            │
│                                                              │
│  /java/analysis-workspace/   ← 持久化 workspace (bind mount)│
│    ├─ AGENTS.md, agent-subagents/, skills/, knowledge/      │
│    ├─ scripts/              ← Python 脚本 (script_exec)      │
│    ├─ memory/               ← per-user MEMORY.md             │
│    ├─ artifacts/            ← CSV 下载等                     │
│    └─ sessions/, user-*/                                    │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ 容器 analysis-project-test  (image: plan-b)            │  │
│  │                                                        │  │
│  │  bind: /app              ← 源码 + jar                  │  │
│  │  bind: /workspace/harness-a2a ← 持久化 workspace       │  │
│  │                                                        │  │
│  │  nginx :80 ──> 宿主机 :18080                            │  │
│  │    ├─ /        → SPA (前端 dist)                       │  │
│  │    └─ /v2/     → proxy_pass 127.0.0.1:8081 (SSE)       │  │
│  │                                                        │  │
│  │  java -jar target/*.jar  (Spring Boot :8081)           │  │
│  │    └─ profiles: dev,docker                             │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## 2. 前置准备

### 2.1 宿主机目录

| 路径 | 用途 | 初始化 |
|---|---|---|
| `/java/analysis-project/analysis-project` | 源码 + prebuilt jar | `git clone` + `mvn package -DskipTests` |
| `/java/analysis-workspace` | 持久化 workspace | 首次 `mkdir -p` 即可,容器启动时 WorkspaceMaterializer 会 seed |

### 2.2 端口

| 宿主机 | 容器 | 用途 |
|---|---|---|
| 18080 | 80 | nginx (前端 + 反代后端) |

后端 8081 不对外暴露,只在容器内 nginx 反代访问。

### 2.3 外部依赖

| 服务 | 地址 | 配置位置 |
|---|---|---|
| MySQL | `jdbc:mysql://111.231.54.6:3306/agentscope` | `application-dev.properties` |
| openGauss | `jdbc:postgresql://116.148.125.236:5432/postgres` | `application-dev.properties` |
| ClickHouse | `jdbc:clickhouse://124.222.194.178:8123/default` | `application-dev.properties` |
| LLM (glm-5.2) | Ark coding channel | `application-dev.properties` |

## 3. Docker 镜像构建

### 3.1 镜像内容

`Dockerfile` 构建 `analysis-project:plan-b` 镜像,包含:

- **基础**: `eclipse-temurin:17-jre` + apt 装 `openjdk-17-crac-jdk-headless` (有 javac 给 mvn 用)
- **工具链**: maven + node + npm + nginx + python3 + pip
- **Python 库**: `pandas`, `sqlalchemy`, `pymysql`, `psycopg2-binary`, `clickhouse-sqlalchemy`, `jpype1`
- **预填缓存**: `.m2` (mvn package 跑一遍) + `/opt/node_modules` (npm ci 跑一遍)
- **配置**: `maven-settings.xml` (阿里云), `pip.conf` (阿里云), `npmrc` (淘宝)

### 3.2 构建命令

```bash
cd /java/analysis-project/analysis-project
docker build -t analysis-project:plan-b .
```

构建耗时约 5-8 分钟 (apt + pip + mvn package + npm ci + npm build)。

### 3.3 何时需要重建镜像

| 改动 | 是否需重建镜像 |
|---|---|
| Java 源码 | ❌ (源码 bind mount 到 /app,用 prebuilt jar) |
| 前端源码 | ❌ (entrypoint 启动时 npm run build) |
| Python 脚本 (`workspace/scripts/`) | ❌ (bind mount 到 /workspace) |
| `Dockerfile` 改动 (加 apt/pip 包) | ✅ |
| `entrypoint.sh` 大改 | ❌ (用 /app/entrypoint.sh 即宿主机版本) |
| `nginx.conf` 改动 | ✅ (COPY 进镜像,或 docker cp) |
| 新增系统级依赖 (如新 pip 包) | ✅ |

## 4. 容器启动

### 4.1 标准启动命令

```bash
docker run -d \
  --name analysis-project-test \
  -p 18080:80 \
  -v /java/analysis-project/analysis-project:/app \
  -v /java/analysis-workspace:/workspace/harness-a2a \
  -e SPRING_PROFILES_ACTIVE=dev,docker \
  analysis-project:plan-b
```

### 4.2 Bind mount 说明

| 宿主机 | 容器 | 作用 |
|---|---|---|
| `/java/analysis-project/analysis-project` | `/app` | 源码 + prebuilt jar + entrypoint.sh + nginx.conf |
| `/java/analysis-workspace` | `/workspace/harness-a2a` | 持久化 workspace (memory/artifacts/sessions/scripts) |

**关键**: `/workspace/harness-a2a` 必须 bind mount,否则容器 recreate 后 memory/artifacts/sessions 全丢。

### 4.3 启动流程 (entrypoint.sh)

```
1. 软链 frontend-pm/node_modules -> /opt/node_modules (用镜像预填的)
2. 检查 /app/target/*.jar:
   - 存在 → 直接用 (jar 直跑模式, 15s 启动)
   - 不存在 → mvn -B -o package -DskipTests (离线构建, 2-3 min)
3. npm run build (前端构建, ~10s)
4. cp dist/* → /usr/share/nginx/html/
5. java -jar target/*.jar --spring.profiles.active=dev,docker &
6. 等后端 health (最长 180s)
7. exec nginx -g 'daemon off;' (前台运行, PID 1)
```

### 4.4 验证启动

```bash
# 看日志
docker logs -f analysis-project-test

# 健康检查
curl http://docker-host:18080/actuator/health

# 预期输出
# {"status":"UP"}
```

正常启动约 35-40s (jar 直跑模式)。

## 5. 配置

### 5.1 Spring Profiles

| Profile | 用途 |
|---|---|
| `dev` | 连接真实 MySQL/openGauss/ClickHouse (在 docker-host 上) |
| `docker` | 容器内路径调整 (workspace.path=/workspace/harness-a2a) |

启动时通过 `SPRING_PROFILES_ACTIVE=dev,docker` 注入。

### 5.2 数据源配置 (`application-dev.properties`)

```properties
# MySQL (agent state, skill index, memory)
spring.datasource.hikari.mysql.jdbc-url=jdbc:mysql://111.231.54.6:3306/agentscope
spring.datasource.hikari.mysql.username=...
spring.datasource.hikari.mysql.driver-class-name=com.mysql.cj.jdbc.Driver

# openGauss (宽表 dsqa_dwd_req_item_app_portrait_wide_inf)
spring.datasource.hikari.gauss.enabled=true
spring.datasource.hikari.gauss.jdbc-url=jdbc:postgresql://116.148.125.236:5432/postgres?sslmode=disable
spring.datasource.hikari.gauss.username=remote_app
spring.datasource.hikari.gauss.driver-class-name=org.postgresql.Driver

# ClickHouse (trace 数据)
spring.datasource.hikari.clickhouse.jdbc-url=jdbc:clickhouse://124.222.194.178:8123/default
spring.datasource.hikari.clickhouse.driver-class-name=com.clickhouse.jdbc.ClickHouseDriver
```

### 5.3 Workspace 配置 (`application-docker.properties`)

```properties
harness.a2a.workspace.path=/workspace/harness-a2a
```

WorkspaceMaterializer 启动时从 jar `classpath:workspace/` 同步到此路径:

| 类别 | 同步规则 | 内容 |
|---|---|---|
| **Always overwrite** | 每次启动覆盖 | `agent-subagents/**`, `AGENTS.md`, `knowledge/**`, `skills/**` |
| **Seeded once** | 首次写入,不覆盖 | `scripts/`, `memory/`, `artifacts/`, `sessions/`, `user-*/` |

### 5.4 openGauss + JPype 配置

psycopg2 不支持 openGauss SHA256 SASL 认证,script_exec 用 JPype + opengauss-jdbc 走 JDBC。

ScriptExecTool 注入以下环境变量给 Python 脚本:

| 环境变量 | 值 | 来源 |
|---|---|---|
| `GAUSS_JDBC_URL` | `jdbc:postgresql://...` | gauss HikariDataSource.jdbcUrl |
| `GAUSS_USER` | 用户名 | gauss HikariDataSource.username |
| `GAUSS_PASS` | 密码 | gauss HikariDataSource.password |
| `GAUSS_JAR` | `/root/.m2/.../opengauss-jdbc-5.1.0.jar` | 硬编码常量 |

Python 脚本通过 `from _gauss_jdbc import query_gauss` 调用,helper 在 `workspace/scripts/_gauss_jdbc.py`。

**关键坑**: `_gauss_jdbc.py` 必须 `import jpype.imports` 才能用 `from java.sql import DriverManager` 语法。

### 5.5 LLM 配置

- 走 Ark coding channel (`/api/coding`),provider=anthropic (不是 OpenAI)
- glm-5.2 模型,32K 上下文
- 配置在 `application-dev.properties` 的 `harness.ark.*` 和 `harness.llm.*`

## 6. 常用操作

### 6.1 重启容器

```bash
docker restart analysis-project-test
```

重启不会丢 workspace (bind mount 持久化),但会重新 npm build + 启动 Spring Boot (~35s)。

### 6.2 更新 Java 代码

```bash
# 1. 宿主机重新打 jar
cd /java/analysis-project/analysis-project
mvn -B package -DskipTests

# 2. 重启容器 (entrypoint 会用新 jar)
docker restart analysis-project-test
```

### 6.3 更新前端代码

```bash
# 前端源码在 /app/frontend-pm/ (bind mount)
# 直接 docker restart 即可,entrypoint 会 npm run build
docker restart analysis-project-test
```

### 6.4 更新 Python 脚本 (`workspace/scripts/`)

⚠️ `scripts/` 是 "seeded once",改源码后 WorkspaceMaterializer **不会**覆盖容器里的旧版本。

```bash
# 方式 1: 手动 cp 到持久化目录 (推荐)
cp src/main/resources/workspace/scripts/_gauss_jdbc.py /java/analysis-workspace/scripts/
cp src/main/resources/workspace/scripts/q2_1_metrics_by_dept_version.py /java/analysis-workspace/scripts/

# 方式 2: 删旧文件让 materializer re-seed (下次启动时)
rm /java/analysis-workspace/scripts/_gauss_jdbc.py
docker restart analysis-project-test
```

### 6.5 更新 AGENTS.md / skills / agent-subagents

这些是 "always overwrite",改源码后 `docker restart` 即可生效。

### 6.6 重建镜像 (加新 pip/apt 包)

```bash
cd /java/analysis-project/analysis-project
docker build -t analysis-project:plan-b .

# 重建后需 recreate 容器 (不是 restart)
docker stop analysis-project-test
docker rm analysis-project-test
docker run -d --name analysis-project-test -p 18080:80 \
  -v /java/analysis-project/analysis-project:/app \
  -v /java/analysis-workspace:/workspace/harness-a2a \
  -e SPRING_PROFILES_ACTIVE=dev,docker \
  analysis-project:plan-b
```

### 6.7 查看后端日志

```bash
# 实时日志
docker exec analysis-project-test tail -f /var/log/backend.log

# 启动日志 (entrypoint 输出)
docker logs analysis-project-test
```

## 7. 注意事项

### 7.1 WorkspaceMaterializer 不覆盖已有文件

`scripts/`, `memory/`, `artifacts/` 等是 "seeded once"。改了源码里的 `workspace/scripts/*.py` 后,必须手动 cp 到 `/java/analysis-workspace/scripts/` 或删旧文件让 materializer re-seed。

### 7.2 prebuilt jar 优先

entrypoint 优先用 `/app/target/*.jar` (宿主机 bind mount)。如果宿主机没打 jar,会 fallback 到容器内 `mvn -B -o package` (离线,依赖镜像预填的 .m2)。**生产环境推荐宿主机先 mvn package 再启动容器**,避免每次启动重打包。

### 7.3 SSE 长连接超时

nginx.conf 配置 `proxy_read_timeout 300s` 给 `/v2/` 路径 (SSE)。agent 推理超过 5 分钟会被 nginx 砍断。如需更长,改 nginx.conf + `docker cp` 进容器或重建镜像。

### 7.4 openGauss 认证

- psycopg2 **不支持** openGauss SHA256 SASL 认证
- 必须用 JPype + opengauss-jdbc (已在镜像里, jar 在 `/root/.m2/...`)
- `_gauss_jdbc.py` 的 `import jpype.imports` 是必须的,否则 `from java.sql import DriverManager` 报 `ModuleNotFoundError: No module named 'java'`

### 7.5 容器 recreate 不丢数据

两个 bind mount 保证:
- `/app` (源码) - 永远是宿主机最新
- `/workspace/harness-a2a` (运行时数据) - 持久化到 `/java/analysis-workspace`

`docker rm` + `docker run` 后,memory/artifacts/sessions/scripts 全部保留。

### 7.6 Flyway 迁移路径

Flyway 读 `classpath:db/migration/mysql/` (不是 `db/migration/` 根)。新迁移脚本放 `src/main/resources/db/migration/mysql/V<日期>.1__<描述>.sql`。

### 7.7 健康检查

Dockerfile HEALTHCHECK 用 `curl -sf http://localhost/actuator/health`。但容器内 nginx 监听 80,后端在 8081。health check 走 nginx 反代到后端。`start-period=300s` 给首次启动留时间。

### 7.8 内存调优

容器默认无内存限制。Spring Boot JVM 堆未显式设置 (用 jar 默认 1/4 容器内存)。如遇 OOM:

```bash
# 加 -m 限制容器内存 + JVM 堆
docker run -d --name analysis-project-test -m 4g \
  -e JAVA_OPTS="-Xms1g -Xmx2g" \
  ...
```

(entrypoint.sh 目前未读 JAVA_OPTS,需改 entrypoint 加上 `java $JAVA_OPTS -jar`)

## 8. 故障排查

### 8.1 后端启动失败

```bash
docker exec analysis-project-test tail -50 /var/log/backend.log
```

常见原因:
- MySQL 连不上 → 检查 `application-dev.properties` 的 mysql 配置
- Flyway 迁移失败 → 检查 `db/migration/mysql/` 下的 SQL
- 端口冲突 → 8081 被占用 (容器内)

### 8.2 前端 502

nginx 起来了但后端没起来:
```bash
docker exec analysis-project-test curl -sf http://127.0.0.1:8081/actuator/health
# 如果失败,看后端日志
docker exec analysis-project-test tail -50 /var/log/backend.log
```

### 8.3 script_exec 报 "GAUSS_JAR 环境变量未设置"

ScriptExecTool 没注入 gauss 环境变量。检查:
- `application-dev.properties` 的 `spring.datasource.hikari.gauss.enabled=true`
- ScriptExecTool 的 `injectGaussJdbcEnv` 方法是否被调用
- 数据源 bean 是否正常注入

### 8.4 JPype 报 "ModuleNotFoundError: No module named 'java'"

`_gauss_jdbc.py` 缺 `import jpype.imports`。检查持久化目录里的脚本:
```bash
grep 'jpype.imports' /java/analysis-workspace/scripts/_gauss_jdbc.py
```

### 8.5 workspace 文件不更新

WorkspaceMaterializer 不覆盖 "seeded once" 文件。手动 cp 或删旧文件:
```bash
# 删旧脚本让 materializer re-seed
rm /java/analysis-workspace/scripts/旧脚本.py
docker restart analysis-project-test
```
