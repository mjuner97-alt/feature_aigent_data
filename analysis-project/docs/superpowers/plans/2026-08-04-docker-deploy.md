# analysis-project 单容器 Docker 源码部署 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在本机 Docker 用源码构建并运行 analysis-project 单容器镜像（nginx + Spring Boot + python3），frontend-pm(React) 走 nginx 静态服务，backend 走 8081 内部端口，对外仅暴露 80。

**Architecture:** 多阶段 Dockerfile（maven 构建 jar + node 构建 React dist -> JRE+nginx+python3 runtime 镜像）。entrypoint.sh 后台启 java、前台跑 nginx。通过新建 `application-docker.properties` profile 覆盖 dev 的 sandbox/remote SSH 设置，使 python_exec 回退到容器本地 python3。

**Tech Stack:** Spring Boot 3.2.3 / Java 17 / Maven 3.9 / Vite 6 + React 18 / nginx / python3 + pandas / Docker BuildKit

## Global Constraints

- 工作目录：`/java/analysis-project/analysis-project`
- 不是 git 仓库，跳过 commit 步骤
- 不修改 `application-dev.properties`、`frontend-pm/Dockerfile`、`frontend-pm/nginx.conf` 原文件
- 新增文件全部放项目根目录或 `src/main/resources/`
- jar 名：`analysis-project-0.0.1-SNAPSHOT.jar`（pom 无 finalName，默认约定）
- 主类：`com.agentscopea2a.AgentscopeA2aApplication`
- 后端端口：8081（dev profile），前端 nginx 端口：80
- LLM env 变量：`LLM_API_KEY` / `LLM_API_URL` / `LLM_MODEL`
- Spring profiles：`dev,docker`（docker 后加载覆盖 dev 的 sandbox/remote）

---

## 文件清单

| 文件 | 责任 |
|------|------|
| `src/main/resources/application-docker.properties` | 覆盖 dev profile 的 sandbox/remote 设置，关掉所有 SSH 依赖 |
| `nginx.conf`（项目根） | 容器内 nginx 配置：静态文件 + 反代 5 条路径到 127.0.0.1:8081，SSE 关 buffer |
| `entrypoint.sh`（项目根） | 后台启动 java -jar，等 health 通过后前台启 nginx |
| `.dockerignore`（项目根） | 排除 node_modules、target、logs、.git 等，缩小 build context |
| `Dockerfile`（项目根） | 三阶段构建：backend jar / frontend dist / runtime |

---

### Task 1: 创建 application-docker.properties

**Files:**
- Create: `src/main/resources/application-docker.properties`

**Interfaces:**
- Produces: Spring Boot profile `docker`，被 `SPRING_PROFILES_ACTIVE=dev,docker` 激活，覆盖 dev profile 的 sandbox/artifacts/skills/memory 远端开关

- [ ] **Step 1: 创建配置文件**

写入 `src/main/resources/application-docker.properties`：

```properties
# ============================================================================
# Docker 单容器部署 profile
# ----------------------------------------------------------------------------
# 与 dev profile 一起激活(spring.profiles.active=dev,docker),
# docker 后加载,覆盖 dev 里的 sandbox / remote SSH 设置,
# 让 python_exec 回退到容器本地 python3(PythonExecTool.buildCommand 第 198 行).
# ============================================================================

# ---- Sandbox: 关掉,python_exec 走容器本地 python3 -------------------------
harness.a2a.sandbox.enabled=false
harness.a2a.sandbox.remote-docker-enabled=false

# ---- 三个 remote 全关,文件落容器本地 ---------------------------------------
harness.a2a.artifacts.remote.enabled=false
harness.a2a.skills.remote.enabled=false
harness.a2a.memory.remote.enabled=false

# ---- workspace 路径用容器内可写目录 ---------------------------------------
# dev profile 默认 .agentscope/workspace/...,容器里以 / 为根,
# 改成绝对路径避免落到不可写位置
harness.a2a.workspace.path=/workspace/harness-a2a
```

- [ ] **Step 2: 验证文件存在且内容正确**

Run: `cat src/main/resources/application-docker.properties`
Expected: 显示上述 6 行配置 + 注释

- [ ] **Step 3: 验证 properties 语法**

Run: `python3 -c "import configparser; c=configconfigparser.ConfigParser(); c.read('src/main/resources/application-docker.properties')"` 失败也没关系（properties 不是 ini），改用：

Run: `grep -c "=" src/main/resources/application-docker.properties`
Expected: 6（6 个键值对）

---

### Task 2: 创建 nginx.conf

**Files:**
- Create: `nginx.conf`（项目根目录）

**Interfaces:**
- Produces: 容器内 `/etc/nginx/conf.d/default.conf`，监听 80，反代 5 条路径到 `127.0.0.1:8081`

- [ ] **Step 1: 创建 nginx 配置**

写入 `nginx.conf`：

```nginx
server {
    listen       80;
    server_name  _;

    root /usr/share/nginx/html;
    index index.html;

    # SPA: 所有非文件请求回退到 index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # ---------------------------------------------------------------------------
    # 反向代理后端 API
    # proxy_pass 末尾不要加 /,否则 nginx 会剥掉前缀(/v2/ai/chat -> /ai/chat)
    # ---------------------------------------------------------------------------
    # /v2/ 走 SseEmitter(后端 V2ChatController),必须:
    #   - proxy_buffering off    关闭缓冲,事件实时推到浏览器
    #   - proxy_read_timeout 300s  长连接超时拉长,避免 agent 推理 60s 被 nginx 砍
    location /v2/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /ai/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_buffering off;
        proxy_read_timeout 300s;
    }

    location /redirect/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # actuator 健康检查端点(供 docker HEALTHCHECK 和外部探测)
    location /actuator/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

- [ ] **Step 2: 验证文件存在**

Run: `test -f nginx.conf && wc -l nginx.conf`
Expected: 显示行数 ~60 行

- [ ] **Step 3: 语法预检（可选，需 nginx 本机有装）**

Run: `nginx -t -c $(pwd)/nginx.conf 2>&1 || echo "nginx not on host, will validate in container"`
Expected: 要么 `syntax is ok`，要么提示本机没装 nginx（容器构建后再验）

---

### Task 3: 创建 entrypoint.sh

**Files:**
- Create: `entrypoint.sh`（项目根目录）

**Interfaces:**
- Produces: 容器 PID 1 进程脚本，启动 java 后台进程 + nginx 前台进程

- [ ] **Step 1: 创建脚本**

写入 `entrypoint.sh`：

```bash
#!/bin/sh
set -e

echo "[entrypoint] starting analysis-project container..."

# Spring Boot profiles,默认 dev,docker,可通过 SPRING_PROFILES_ACTIVE 覆盖
SPRING_PROFILES="${SPRING_PROFILES_ACTIVE:-dev,docker}"

# 后台启动 Spring Boot
echo "[entrypoint] launching spring boot with profiles=${SPRING_PROFILES}"
java -jar /app/app.jar \
  --spring.profiles.active="${SPRING_PROFILES}" \
  > /var/log/backend.log 2>&1 &

# 等 backend 起来(最长 90s,Spring Boot 3 + agentscope 首次启动较慢)
echo "[entrypoint] waiting for backend health..."
for i in $(seq 1 90); do
  if curl -sf http://127.0.0.1:8081/actuator/health > /dev/null 2>&1; then
    echo "[entrypoint] backend ready after ${i}s"
    break
  fi
  if [ "$i" = "90" ]; then
    echo "[entrypoint] WARNING: backend not ready after 90s, nginx will return 502"
    echo "[entrypoint] last 30 lines of backend.log:"
    tail -30 /var/log/backend.log 2>/dev/null || true
  fi
  sleep 1
done

# 前台运行 nginx (PID 1)
echo "[entrypoint] starting nginx on :80..."
exec nginx -g 'daemon off;'
```

- [ ] **Step 2: 加可执行权限**

Run: `chmod +x entrypoint.sh`
Expected: 无输出

- [ ] **Step 3: 验证脚本语法**

Run: `sh -n entrypoint.sh && echo "syntax ok"`
Expected: `syntax ok`

- [ ] **Step 4: 验证可执行位**

Run: `ls -l entrypoint.sh`
Expected: 权限位包含 `x`（如 `-rwxr-xr-x`）

---

### Task 4: 创建 .dockerignore

**Files:**
- Create: `.dockerignore`（项目根目录）

**Interfaces:**
- Produces: docker build context 过滤规则，避免把 node_modules / target / logs 等大目录发给 daemon

- [ ] **Step 1: 创建文件**

写入 `.dockerignore`：

```
# 日志和大文件
*.log
app.log
backend.log
boot.log
build.log
frontend.log
restart.log

# 构建产物(容器内重新构建,不挂宿主机产物)
target/
frontend/node_modules/
frontend-pm/node_modules/
frontend/dist/
frontend-pm/dist/
frontend/.vite/

# 临时文件
tmp/
tmp_test/
.classpath.txt
.claude-smoke-query.json
tmp_testboot_log_fingerprint.txt

# IDE / 版本控制
.git/
.gitignore
.idea/
.vscode/

# 文档(不打进镜像)
docs/

# agentscope workspace(运行时生成,不需要打进镜像)
.agentscope/

# 已有的 frontend-pm Docker 构建产物(如果有)
frontend-pm/Dockerfile
frontend-pm/.dockerignore
```

- [ ] **Step 2: 验证文件存在**

Run: `test -f .dockerignore && wc -l .dockerignore`
Expected: 显示行数 ~30 行

- [ ] **Step 3: 检查是否会误排除构建必需文件**

Run: `grep -E "^(pom\.xml|src/|frontend-pm/src/|frontend-pm/package|frontend-pm/index|frontend-pm/vite|frontend-pm/tsconfig)" .dockerignore`
Expected: 无输出（这些文件不能在 .dockerignore 里）

---

### Task 5: 创建 Dockerfile

**Files:**
- Create: `Dockerfile`（项目根目录）

**Interfaces:**
- Consumes: `pom.xml` + `src/`（backend）、`frontend-pm/`（frontend）、`nginx.conf`、`entrypoint.sh`（runtime）
- Produces: 镜像 `analysis-project:latest`，端口 80，入口 `/entrypoint.sh`

- [ ] **Step 1: 创建 Dockerfile**

写入 `Dockerfile`：

```dockerfile
# syntax=docker/dockerfile:1.6

# ============================================================================
# Stage 1: backend builder - Maven 构建 Spring Boot fat jar
# ============================================================================
FROM maven:3.9-eclipse-temurin-17 AS backend-builder

WORKDIR /build

# 先拷贝 pom,利用缓存层下依赖
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B

# 拷贝源码,打包(跳过测试,本机部署不需要)
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -B \
    && ls -lh target/*.jar

# ============================================================================
# Stage 2: frontend builder - Vite 构建 React 静态资源
# ============================================================================
FROM node:20-alpine AS frontend-builder

WORKDIR /app

# 先拷贝依赖描述,利用缓存层
COPY frontend-pm/package.json frontend-pm/package-lock.json ./
RUN npm ci

# 拷贝源码,构建到 dist/(覆盖 vite.config.ts 默认 outDir)
COPY frontend-pm/ ./
ENV VITE_OUT_DIR=dist
RUN npm run build \
    && ls -lh dist/

# ============================================================================
# Stage 3: runtime - JRE + nginx + python3
# ============================================================================
FROM eclipse-temurin:17-jre

# 安装 nginx + python3 + curl(healthcheck 用)
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        nginx \
        python3 \
        python3-pip \
        curl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && rm -f /etc/nginx/sites-enabled/default

# pip 安装 agent python_exec 可能用到的库
# pandas: SKILL.md 里 import pandas as pd
# sqlalchemy + 三个 DB driver: 万一 agent 拼 connection string 查数
RUN pip3 install --no-cache-dir --break-system-packages \
        pandas \
        sqlalchemy \
        pymysql \
        psycopg2-binary \
        clickhouse-sqlalchemy

# 拷贝 backend jar
COPY --from=backend-builder /build/target/analysis-project-0.0.1-SNAPSHOT.jar /app/app.jar

# 拷贝 frontend dist 到 nginx html 根目录
COPY --from=frontend-builder /app/dist /usr/share/nginx/html/

# 拷贝 nginx 配置(覆盖默认 default.conf)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 拷贝 entrypoint
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# 创建可写工作目录(agentscope workspace + artifacts 落这里)
RUN mkdir -p /workspace /var/log

# 健康检查:nginx 代理 /actuator/health 到 backend
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -sf http://localhost/actuator/health || exit 1

EXPOSE 80

ENTRYPOINT ["/entrypoint.sh"]
```

- [ ] **Step 2: 验证 Dockerfile 语法**

Run: `docker build --check -f Dockerfile . 2>&1 | tail -20 || true`
Expected: 无 error（warning 可接受）。如果 docker 不支持 `--check`，跳过此步。

- [ ] **Step 3: 验证 BuildKit 启用**

Run: `echo $DOCKER_BUILDKIT`
Expected: `1` 或空（默认启用）。如果为 0，构建时用 `DOCKER_BUILDKIT=1 docker build ...`

---

### Task 6: 构建镜像

**Files:**
- 无文件变更，执行构建

- [ ] **Step 1: 设置 BuildKit 并开始构建**

Run: `DOCKER_BUILDKIT=1 docker build -t analysis-project:latest . 2>&1 | tee /tmp/docker-build.log | tail -50`

Expected:
- Stage 1 (backend-builder): `mvn clean package -DskipTests` 输出 `BUILD SUCCESS`，`target/analysis-project-0.0.1-SNAPSHOT.jar` 生成
- Stage 2 (frontend-builder): `npm run build` 输出 `dist/` 目录
- Stage 3 (runtime): apt 安装 nginx/python3，pip 安装 pandas 等，拷贝 jar/dist/conf
- 最后 `Successfully tagged analysis-project:latest`

首次构建预计 5-10 分钟（maven 下依赖 + npm ci + apt + pip）。

- [ ] **Step 2: 检查构建日志关键节点**

Run: `grep -E "BUILD SUCCESS|npm run build|Successfully|ERROR|FAILED" /tmp/docker-build.log | head -20`
Expected: 看到 `BUILD SUCCESS` 和 `Successfully tagged` / `naming to ... analysis-project:latest`

- [ ] **Step 3: 验证镜像存在**

Run: `docker images analysis-project:latest`
Expected: 显示一行，REPOSITORY=`analysis-project`，TAG=`latest`，SIZE ~800MB-1.2GB

- [ ] **Step 4: 验证镜像 entrypoint 和暴露端口**

Run: `docker inspect analysis-project:latest --format '{{.Config.Entrypoint}} | expose: {{json .Config.ExposedPorts}}'`
Expected: `[/entrypoint.sh] | expose: {"80/tcp":{}}`

---

### Task 7: 运行容器并 smoke test

**Files:**
- 无文件变更，运行容器

- [ ] **Step 1: 启动容器（后台）**

Run: `docker run -d --name analysis-project-test -p 8080:80 -e SPRING_PROFILES_ACTIVE=dev,docker analysis-project:latest`
Expected: 输出一长串容器 ID

注意：主机 80 可能被占用，映射到 8080 避免冲突。

- [ ] **Step 2: 等待容器启动并观察日志**

Run: `sleep 10 && docker logs analysis-project-test 2>&1 | tail -30`
Expected: 看到 `[entrypoint] starting analysis-project container...` 和 `launching spring boot with profiles=dev,docker`

- [ ] **Step 3: 等待 Spring Boot 起来（最长 90s）**

Run: `for i in $(seq 1 90); do if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then echo "READY after ${i}s"; break; fi; sleep 2; done`
Expected: `READY after Ns`，N 通常在 30-60 之间

如果 90s 还没起来：
- `docker logs analysis-project-test 2>&1 | tail -50` 看 backend.log 报错
- `docker exec analysis-project-test cat /var/log/backend.log | tail -80` 看 Java 异常

- [ ] **Step 4: 验证前端静态资源**

Run: `curl -sI http://localhost:8080/ | head -5`
Expected: `HTTP/1.1 200 OK`，`Content-Type: text/html`

Run: `curl -s http://localhost:8080/ | head -20`
Expected: 看到 `<html>`、`<div id="root">` 等 React index.html 标记

- [ ] **Step 5: 验证 actuator health**

Run: `curl -s http://localhost:8080/actuator/health`
Expected: `{"status":"UP"}` 或带 components 的 UP 状态

如果 status 是 DOWN：
- `docker exec analysis-project-test curl -s http://127.0.0.1:8081/actuator/health` 直连后端看详情
- 多半是某个 DataSource 连不上（dev profile 的远程 IP 不通）

- [ ] **Step 6: 验证 python3 可用**

Run: `docker exec analysis-project-test python3 -c "import pandas; print('pandas', pandas.__version__)"`
Expected: `pandas 2.x.x`

Run: `docker exec analysis-project-test python3 -c "import sqlalchemy, pymysql, psycopg2, clickhouse_sqlalchemy; print('all drivers ok')"`
Expected: `all drivers ok`

- [ ] **Step 7: 验证 nginx 进程在跑**

Run: `docker exec analysis-project-test ps aux | grep -E "nginx|java" | grep -v grep`
Expected: 看到一个 `nginx: master process` + 一个 `nginx: worker process` + 一个 `java -jar /app/app.jar`

- [ ] **Step 8: 浏览器实测（手动）**

打开浏览器访问 `http://localhost:8080/`：
- React 页面正常加载（无白屏）
- F12 Network 看静态资源 200
- 尝试发一条对话，Network 里 `/v2/ai/chat` 走 SSE，事件流式返回（不是一次性 200）

---

### Task 8: 清理与交付

**Files:**
- 无文件变更

- [ ] **Step 1: 停止并删除测试容器**

Run: `docker rm -f analysis-project-test`
Expected: `analysis-project-test`

- [ ] **Step 2: 列出交付物**

Run: `ls -la Dockerfile nginx.conf entrypoint.sh .dockerignore src/main/resources/application-docker.properties`
Expected: 5 个文件全部存在

- [ ] **Step 3: 输出最终运行命令（给用户）**

向用户输出：

```
镜像已构建: analysis-project:latest
启动命令:
  docker run -d \
    --name analysis-project \
    -p 80:80 \
    -e SPRING_PROFILES_ACTIVE=dev,docker \
    -e LLM_API_KEY=<your-key> \
    analysis-project:latest

查看日志: docker logs -f analysis-project
健康检查: curl http://localhost/actuator/health
进容器:   docker exec -it analysis-project bash
```

---

## Self-Review

**1. Spec 覆盖：**
- Section 1-3（架构/范围/目标）：Task 5 Dockerfile + Task 7 验证 ✓
- Section 4（多阶段构建）：Task 5 三阶段 Dockerfile ✓
- Section 5（配置覆盖）：Task 1 application-docker.properties ✓
- Section 6（nginx.conf）：Task 2 ✓，含 SSE buffer off、actuator 代理
- Section 7（entrypoint.sh）：Task 3 ✓，含 health 等待、SPRING_PROFILES_ACTIVE env
- Section 8（数据流）：Task 7 step 4-8 验证 ✓
- Section 9（错误处理 healthcheck）：Task 5 Dockerfile HEALTHCHECK + Task 7 step 3/5 错误处理 ✓
- Section 10（测试验证）：Task 6-7 全覆盖 ✓
- Section 11（交付物清单）：Task 8 step 2 ✓

**2. Placeholder 扫描：** 无 TBD/TODO，所有 step 有具体命令或代码 ✓

**3. 类型/路径一致性：**
- jar 路径 `/app/app.jar`：Dockerfile COPY + entrypoint.sh `java -jar` 一致 ✓
- nginx.conf 路径 `/etc/nginx/conf.d/default.conf`：Dockerfile COPY 一致 ✓
- dist 路径 `/usr/share/nginx/html/`：Dockerfile COPY + nginx.conf `root` 一致 ✓
- profiles `dev,docker`：entrypoint.sh 默认值 + Task 7 env var 一致 ✓
- 端口 8080 主机映射：Task 7 用 8080 避免冲突，Task 8 输出 80 给生产 ✓

**4. 风险点：**
- 主机 80 可能被占用 -> Task 7 step 1 注释说明，用 8080 测试
- Spring Boot 首次启动慢 -> Task 3 entrypoint 等 90s，Task 7 step 3 90s 循环
- dev profile 远程 DB IP 不通 -> Task 7 step 5 错误处理说明
- pip `--break-system-packages`：eclipse-temurin:17-jre 基于 Ubuntu 24.04，PEP 668 限制，必须加此 flag

