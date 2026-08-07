# Docker 源码部署 analysis-project 设计文档

**日期**：2026-08-04
**目标**：在本机 Docker 环境用源码构建并运行 analysis-project 前后端，单容器拓扑，复用 dev profile 的远程数据源配置。

## 1. 范围与目标

- **部署目标**：本机/单机跑通即可，不追求生产可用。
- **前端选型**：`frontend-pm/`（React + Vite + nginx），不走 `frontend/`（Vue）。
- **容器拓扑**：单容器，nginx + Spring Boot + python3 三个进程。
- **Sandbox**：全关。`python_exec` 回退到容器本地 `python3 -`（`PythonExecTool.buildCommand()` 第 198 行的 fallback 路径）。
- **数据源**：复用 `application-dev.properties` 里已配好的远程 MySQL / ClickHouse / openGauss / DeepSeek / Ollama embedding 地址，容器走 bridge 网络出公网访问。

## 2. 非目标

- 不启用 sandbox 容器隔离（agent 代码在 backend 容器内直接跑 python3）。
- 不部署本地 MySQL/ClickHouse/openGauss 容器（用现有远程实例）。
- 不做 k8s/CI 集成。
- 不动 `frontend/`（Vue）代码。

## 3. 架构

```
┌──────────────────────────────────────────────────┐
│  Container (analysis-project:latest)             │
│  Port: 80 (nginx)                                │
│                                                  │
│  ┌────────────┐   proxy_pass    ┌─────────────┐  │
│  │  nginx :80 │ ──────────────> │ Spring Boot │  │
│  │  (静态文件) │  /v2 /api /ai   │   :8081     │  │
│  └────────────┘   /redirect      └─────┬───────┘  │
│           /actuator/                    │          │
│                                python3  │          │
│                                (PATH)   │          │
│                                   ▼     │          │
│                              ┌──────────────┐     │
│                              │ pandas +     │     │
│                              │ DB drivers   │     │
│                              └──────────────┘     │
└──────────────────────────────────────────────────┘
       │
       │ 出公网 (bridge 网络)
       ▼
   MySQL / ClickHouse / openGauss / DeepSeek API / Ollama embedding
```

- **PID 1**：nginx（`daemon off;` 前台运行）。nginx 挂了容器即重启。
- **Java 进程**：entrypoint.sh 后台启动 `java -jar`，崩了由 healthcheck 暴露（nginx 返回 502）。
- **端口暴露**：仅 `80`。`8081` 仅容器内 `127.0.0.1` 可达，不对外暴露，避免绕过 nginx。

## 4. 多阶段 Dockerfile

### Stage 1：backend builder

- 基础镜像：`maven:3.9-eclipse-temurin-17`
- 步骤：
  1. `COPY pom.xml` + `RUN mvn dependency:go-offline`（缓存依赖层）
  2. `COPY src ./src` + `RUN mvn clean package -DskipTests`
- 产物：`target/analysis-project-0.0.1-SNAPSHOT.jar`
- 缓存优化：BuildKit `--mount=type=cache,target=/root/.m2` 挂主机 maven cache
- 注意：`src/main/resources/static/` 里的 Vue 旧产物会被打进 jar，但运行时 nginx 独立 serve React dist，jar 里的 static 不会被访问。

### Stage 2：frontend builder

- 基础镜像：`node:20-alpine`
- 步骤：
  1. `COPY frontend-pm/package*.json ./` + `RUN npm ci`
  2. `COPY frontend-pm/ ./` + `RUN VITE_OUT_DIR=dist npm run build`
- 产物：`/app/dist/`（index.html + assets/）

### Stage 3：runtime

- 基础镜像：`eclipse-temurin:17-jre`（基于 Ubuntu，apt 可用）
- 安装：`nginx`、`python3`、`python3-pip`、`curl`（healthcheck 用）
- pip 安装：`pandas`、`sqlalchemy`、`pymysql`、`psycopg2-binary`、`clickhouse-sqlalchemy`
- 拷贝：
  - `COPY --from=backend-builder /build/target/analysis-project-0.0.1-SNAPSHOT.jar /app/app.jar`
  - `COPY --from=frontend-builder /app/dist /usr/share/nginx/html/`
  - `COPY nginx.conf /etc/nginx/conf.d/default.conf`
  - `COPY entrypoint.sh /entrypoint.sh` + `RUN chmod +x /entrypoint.sh`
- `ENTRYPOINT ["/entrypoint.sh"]`

## 5. 配置覆盖

新建 `src/main/resources/application-docker.properties`：

```properties
# 关掉 sandbox,python_exec 走容器本地 python3
harness.a2a.sandbox.enabled=false
harness.a2a.sandbox.remote-docker-enabled=false

# 三个 remote 全关,文件落容器本地
harness.a2a.artifacts.remote.enabled=false
harness.a2a.skills.remote.enabled=false
harness.a2a.memory.remote.enabled=false

# workspace 路径用容器内可写目录
harness.a2a.workspace.path=/workspace/harness-a2a
```

- 激活方式：`SPRING_PROFILES_ACTIVE=dev,docker`（docker profile 后加载，覆盖 dev 的 sandbox/remote 设置）。
- LLM key 覆盖：`LLM_API_KEY` / `LLM_API_URL` / `LLM_MODEL` 三个环境变量（`application.properties` 第 30-32 行已支持 `${LLM_API_KEY:...}` 占位，`verify` / `critic` 实例也共用这三个 env）。

## 6. nginx.conf

基于 `frontend-pm/nginx.conf`，改动：

1. `proxy_pass` 从硬编码 `http://192.168.101.2:8081` 改为 `http://127.0.0.1:8081`
2. `/v2/` 块加 `proxy_buffering off` + `proxy_read_timeout 300s`（SSE 流式必需，否则事件被 nginx buffer 住）
3. 加 `location /actuator/` 代理，方便从外部看健康状态
4. 加 `location /api/`、`location /ai/`、`location /redirect/`（原版只有 `/v2/`，但 `vite.config.ts` 里 dev server 代理了这四条，生产 nginx 也要全配）

完整配置见实现阶段。

## 7. entrypoint.sh

```bash
#!/bin/sh
set -e

# Spring Boot profiles, 默认 dev,docker,可通过 env 覆盖
SPRING_PROFILES=${SPRING_PROFILES_ACTIVE:-dev,docker}

# 后台启动 Spring Boot
java -jar /app/app.jar \
  --spring.profiles.active=${SPRING_PROFILES} \
  > /var/log/backend.log 2>&1 &

# 等 backend 起来(最长 60s)
for i in $(seq 1 60); do
  if curl -sf http://127.0.0.1:8081/actuator/health > /dev/null 2>&1; then
    echo "backend ready"
    break
  fi
  sleep 1
done

# 前台运行 nginx (PID 1)
nginx -g 'daemon off;'
```

- jar 拷贝目标：Stage 3 里 `COPY --from=builder /build/target/analysis-project-0.0.1-SNAPSHOT.jar /app/app.jar`（重命名为 `app.jar` 固定路径，避免 pom version 变动改 entrypoint）。
- `SPRING_PROFILES_ACTIVE` env var 优先，未设则用 `dev,docker`。

## 8. 数据流

1. 浏览器 -> `:80` -> nginx
2. 静态资源（`/`、`/assets/*`）-> nginx 直接返回 React dist
3. API（`/v2/*`、`/api/*`、`/ai/*`、`/redirect/*`、`/actuator/*`）-> nginx proxy -> `127.0.0.1:8081` -> Spring Boot
4. Spring Boot 调 MySQL/ClickHouse/openGauss/DeepSeek/Ollama -> 容器 bridge -> 公网
5. Agent 调 `python_exec` -> `python3 -` 子进程（容器内）-> 读 CSV/算数 -> 返回结果

## 9. 错误处理与 healthcheck

- **Java 挂了**：nginx 502。`HEALTHCHECK curl -f http://localhost/actuator/health`，失败 3 次容器重启。
- **nginx 挂了**：PID 1 退出，容器直接重启。
- **DB 连不上**：actuator health DOWN，容器不重启，`docker logs` 看日志。
- **python_exec 缺包**：返回错误给 agent，agent 重试或换工具。可后期按需补包。

## 10. 测试验证

- `docker build` 成功，镜像 < 1.2GB
- `docker run -p 80:80 -e SPRING_PROFILES_ACTIVE=dev,docker analysis-project:latest` 启动
- `curl http://localhost/` 返回 React index.html
- `curl http://localhost/actuator/health` 返回 `{"status":"UP"}`
- 浏览器打开 `http://localhost/`，前端正常加载，发一条对话，SSE 流式返回
- `docker exec <container> python3 -c "import pandas; print(pandas.__version__)"` 验证 python 可用

## 11. 交付物清单

- `Dockerfile`（项目根目录，多阶段）
- `nginx.conf`（项目根目录，覆盖 frontend-pm 的版本，避免改原文件）
- `entrypoint.sh`（项目根目录）
- `.dockerignore`（项目根目录）
- `src/main/resources/application-docker.properties`
- 不动 `frontend-pm/Dockerfile` / `frontend-pm/nginx.conf`（原文件保留）
- 不动 `application-dev.properties`（通过 profile 覆盖，不修改原文件）

## 12. 风险与已知问题

- **镜像体积**：JRE + nginx + python + pandas 估计 ~800MB-1.1GB。可接受。
- **构建时间**：首次 mvn package 在容器内 ~3-5min（无 m2 cache）；用 BuildKit cache mount 可降到 ~1min。
- **dev profile 远程 DB IP**：`111.231.54.6`、`124.222.194.178`、`116.148.125.236` 是公网 IP，容器 bridge 网络可访问，但依赖主机出公网能力。
- **`harness.a2a.sandbox.enabled=flase` 拼写错误**：`application.properties` 第 189 行有 typo（`flase`），不等于 `true`，所以全局默认 sandbox 是关的；dev profile 第 86 行又覆盖成 `true`。docker profile 再次覆盖为 `false`，最终生效为关。无需修原文件。
- **前端 hardcoded IP**：原 `frontend-pm/nginx.conf` 的 `192.168.101.2:8081` 不可用，本设计用根目录新 nginx.conf 替换，不改原文件。
