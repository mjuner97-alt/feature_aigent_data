# analysis-project Docker 源码部署 - 方案 B（容器内构建）

**日期**：2026-08-04
**目标**：宿主机零依赖（无 maven/node），仅源码 + Docker，改代码后 `docker restart` 即更新。

---

## 1. 方案对比

| | 方案 A（多阶段 build） | **方案 B（容器内构建，本文档）** |
|---|---|---|
| 更新命令 | `docker build` + `docker restart` | `docker restart` |
| 更新耗时 | ~30s-1min（增量缓存） | ~70-80s（全量 mvn + vite + spring boot） |
| 镜像大小 | 634MB | 1.64GB |
| 宿主机依赖 | 无 | 无 |
| 构建工具位置 | build 时在镜像构建层 | 运行时在容器内 |

**选 B 的理由**：内网宿主机无 maven/node 环境，改代码后只想 `docker restart`，不想 `docker build`。

---

## 2. 架构

```
┌──────────────────────────────────────────────────────────────┐
│  Host (内网宿主机, 仅有源码, 无 maven/node)                   │
│  /java/analysis-project/analysis-project/  ← 源码 (bind mount)│
└───────────────────────────┬──────────────────────────────────┘
                            │ -v /host/source:/app
┌───────────────────────────▼──────────────────────────────────┐
│  Container (analysis-project:plan-b, 1.64GB)                 │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  entrypoint.sh 启动时:                                 │  │
│  │  1. 软链 frontend-pm/node_modules -> /opt/node_modules  │  │
│  │  2. mvn package (用预填的 .m2, 43s)                    │  │
│  │  3. npm run build (用 /opt/node_modules, 5.6s)         │  │
│  │  4. cp dist -> nginx html                              │  │
│  │  5. java -jar (Spring Boot :8081)                      │  │
│  │  6. nginx :80 (前台 PID 1)                             │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  预填缓存 (build 时烤进镜像, 内网离线可跑):                   │
│  - /root/.m2          (161MB, maven 依赖)                    │
│  - /opt/node_modules  (73MB,  npm 依赖)                      │
│                                                              │
│  端口: 80 (nginx) -> 18080 (宿主机)                          │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. 前置条件

- Docker 已安装，能拉 `gh-proxy.org/docker/eclipse-temurin:17-jre`（295MB，做 base）
- 源码在 `/java/analysis-project/analysis-project/`（含 `pom.xml`、`frontend-pm/`、`src/`、`nginx.conf`、`entrypoint.sh`、`Dockerfile`、`maven-settings.xml`、`pip.conf`、`npmrc`）
- **宿主机不需要** maven / node / npm / java

---

## 4. 文件清单

| 文件 | 责任 |
|------|------|
| `Dockerfile` | 单阶段：17-jre base + apt install openjdk-17/maven/node22/npm/nginx/python3 + 预填 .m2 和 node_modules |
| `entrypoint.sh` | 启动时：软链 node_modules → mvn package → npm run build → cp dist → java -jar → nginx |
| `nginx.conf` | nginx 配置：静态文件 + 反代 `/v2 /api /ai /redirect /actuator` 到 `127.0.0.1:8081` |
| `maven-settings.xml` | aliyun maven 镜像（加速 build 时依赖下载） |
| `pip.conf` | aliyun PyPI 镜像（加速 build 时 python 库下载） |
| `npmrc` | npmmirror 镜像（加速 build 时 npm 包下载） |
| `.dockerignore` | 排除 target/、node_modules/、dist/、日志等，避免打进镜像 |
| `pom.xml` | 依赖声明（含 flyway-core + flyway-mysql，FlywayConfig.java 需要） |

---

## 5. 构建镜像

### 5.1 在有网的机器上构建（一次）

```bash
cd /java/analysis-project/analysis-project
docker build -t analysis-project:plan-b .
```

**构建耗时**：首次约 10-15 分钟（apt install + pip install + mvn package + npm ci + npm run build）。后续构建有缓存，只重跑变更层。

**构建过程**：
1. apt install openjdk-17-crac-jdk-headless + maven 3.9 + nodejs 22 + npm 9 + nginx + python3 + curl + git
2. 配置 aliyun 镜像（maven/pip/npm）
3. pip install pandas/sqlalchemy/pymysql/psycopg2-binary/clickhouse-sqlalchemy
4. `COPY . /tmp-build` → `mvn package`（预填 .m2，约 1.5min）
5. `COPY frontend-pm/ /tmp-build-frontend/` → `npm ci` + `npm run build`（预填 /opt/node_modules，约 15s）
6. 拷贝 nginx.conf、entrypoint.sh
7. 产出镜像 `analysis-project:plan-b`（1.64GB）

### 5.2 导出镜像（传到内网宿主机）

```bash
docker save analysis-project:plan-b | gzip > analysis-project-plan-b.tar.gz
# 传到内网宿主机后:
docker load < analysis-project-plan-b.tar.gz
```

---

## 6. 启动容器

### 6.1 docker run

```bash
docker run -d --name analysis-project-test \
    -p 18080:80 \
    -e SPRING_PROFILES_ACTIVE=dev,docker \
    -v /java/analysis-project/analysis-project:/app \
    analysis-project:plan-b
```

**参数说明**：
- `-p 18080:80`：宿主机 18080 → 容器 80（nginx）
- `-e SPRING_PROFILES_ACTIVE=dev,docker`：Spring Boot profiles，docker profile 覆盖 dev 的 sandbox/SSH 设置
- `-v /java/analysis-project/analysis-project:/app`：源码 bind mount 到容器 /app
- `analysis-project:plan-b`：镜像名

### 6.2 启动过程（约 70-80s）

```
[entrypoint] starting analysis-project (方案 B: 容器内构建)...
[entrypoint] symlinked frontend-pm/node_modules -> /opt/node_modules
[entrypoint] mvn package (offline)...
[INFO] BUILD SUCCESS
[INFO] BUILD SUCCESS
[INFO] Total time:  43.252 s
[entrypoint] npm run build...
✓ built in 5.61s
[entrypoint] copying dist to nginx html...
[entrypoint] launching spring boot with profiles=dev,docker
[entrypoint] waiting for backend health...
[entrypoint] backend ready after 32s
[entrypoint] starting nginx on :80...
```

### 6.3 验证

```bash
# 健康检查
curl http://localhost:18080/actuator/health
# 期望: {"status":"UP","groups":["liveness","readiness"]}

# 前端首页
curl -s http://localhost:18080/ | grep -o '<title>[^<]*</title>'
# 期望: <title>PlanNotebook + 状态机</title>
```

---

## 7. 更新流程（改代码 + 重启即更新）

### 7.1 改前端代码

```bash
# 编辑 frontend-pm/src/ 下的代码
vim frontend-pm/src/App.tsx

# 重启容器
docker restart analysis-project-test

# 等 ~70s, 刷新浏览器即可看到新内容
```

### 7.2 改后端代码

```bash
# 编辑 src/main/java/ 下的代码
vim src/main/java/com/agentscopea2a/...

# 重启容器
docker restart analysis-project-test

# 等 ~70s
```

### 7.3 改 pom.xml（加新依赖）

```bash
# 编辑 pom.xml 加新依赖
vim pom.xml

# 重启容器
# ⚠️ mvn -o 离线模式: 如果新依赖不在 .m2 缓存里, mvn 直接报错 (fail fast)
# 解决: 在有网的机器上重新 docker build (build 时非离线, 会下载新依赖到 .m2), 再导出镜像到内网
docker restart analysis-project-test
```

### 7.4 重启耗时分解

| 阶段 | 耗时 | 说明 |
|------|------|------|
| docker restart 命令 | 1.5s | 只是发重启信号 |
| mvn package | ~43s | 用预填 .m2, 不下载依赖 |
| npm run build | ~5.6s | 用 /opt/node_modules |
| Spring Boot 启动 | ~32s | agentscope 首次初始化较慢 |
| **总计** | **~70-80s** | curl 健康检查返回 UP 即就绪 |

---

## 8. 配置文件详解

### 8.1 Dockerfile 关键设计

```dockerfile
FROM gh-proxy.org/docker/eclipse-temurin:17-jre

# apt 装 JDK (有 javac 给 maven 用) + maven + node + nginx + python3
RUN apt-get install -y openjdk-17-crac-jdk-headless maven nodejs npm nginx python3 ...

# JAVA_HOME 指向 Ubuntu OpenJDK (不是 Temurin JRE)
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-crac-amd64

# 预填 .m2: build 时用当前源码跑一次 mvn package
COPY . /tmp-build
RUN cd /tmp-build && mvn -B package -DskipTests && rm -rf /tmp-build

# 预填 node_modules: npm ci + npm run build, 移到 /opt/node_modules
COPY frontend-pm/ /tmp-build-frontend/
RUN cd /tmp-build-frontend && npm ci && npm run build \
    && mv node_modules /opt/node_modules && rm -rf /tmp-build-frontend
```

**为什么用 17-jre + apt install openjdk-17 而不是直接用 17-jdk？**
- 17-jdk 镜像拉取太慢（gh-proxy.org 网络问题，5 分钟拉了 100MB）
- 17-jre 已缓存（295MB），apt install openjdk-17-crac-jdk-headless 从 Ubuntu 源装（快）
- 最终镜像一样有完整 JDK（javac + java）

### 8.2 entrypoint.sh 关键逻辑

```bash
# 1. 软链 node_modules (用镜像里预填的 /opt/node_modules, 避免内网重新下载)
if [ ! -L /app/frontend-pm/node_modules ]; then
    rm -rf /app/frontend-pm/node_modules  # 删掉宿主机的 node_modules
    ln -s /opt/node_modules /app/frontend-pm/node_modules
fi

# 2. mvn package (离线模式, 只用预填 .m2, 不查远程仓库)
mvn -B -o package -DskipTests

# 3. npm run build (用软链过来的 /opt/node_modules)
cd /app/frontend-pm && VITE_OUT_DIR=dist npm run build

# 4. 拷贝 dist 到 nginx html
cp -r /app/frontend-pm/dist/* /usr/share/nginx/html/

# 5. 后台启 Spring Boot, 前台跑 nginx
java -jar /app/target/analysis-project-0.0.1-SNAPSHOT.jar &
exec nginx -g 'daemon off;'
```

### 8.3 镜像配置（aliyun 镜像）

| 文件 | 内容 | 作用 |
|------|------|------|
| `maven-settings.xml` | `<mirrorOf>*</mirrorOf>` → `https://maven.aliyun.com/repository/public` | maven 依赖下载加速（PyPI 直连 22KB/s，aliyun 3.25MB/s） |
| `pip.conf` | `index-url = https://mirrors.aliyun.com/pypi/simple/` | pip 下载加速 |
| `npmrc` | `registry=https://registry.npmmirror.com/` | npm 下载加速 |

**内网离线时**：`mvn -o`（offline）强制只用 .m2 缓存，不访问远程仓库，避免无外网时每个依赖超时 30s。npm 用 /opt/node_modules，不查 registry。两者都不需要网络。

---

## 9. 注意事项

### 9.1 宿主机 frontend-pm/node_modules 被改成软链

容器首次启动时，entrypoint 会把宿主机的 `frontend-pm/node_modules` 删掉，替换成指向容器内 `/opt/node_modules` 的软链。

**在宿主机上这个软链是断的**（`/opt/node_modules` 只存在于容器里）。

如果要在宿主机跑 npm（比如用 IDE 的 TypeScript 检查）：
```bash
rm frontend-pm/node_modules  # 删断链
npm ci                       # 重新装 (需要宿主机有 node + 网络)
```

容器下次启动时，entrypoint 会再次把 `node_modules` 替换成软链。

### 9.2 宿主机 target/ 会被容器写入

容器的 `mvn package` 产物写在 `/app/target/`（bind mount 到宿主机 `target/`）。容器跑完后宿主机 `target/` 里会有新的 jar。这是正常的，不影响功能。

### 9.3 改 pom.xml 加新依赖

内网宿主机无外网时，新依赖下载失败会导致 `mvn package` 报错。解决：
1. 在有网的机器上改 pom.xml
2. `docker build -t analysis-project:plan-b .`（重新构建镜像，预填新依赖到 .m2）
3. `docker save | gzip > ...` 导出镜像
4. 传到内网，`docker load` 导入
5. `docker restart analysis-project-test`

### 9.4 改 Dockerfile / entrypoint.sh / nginx.conf

这些文件烤在镜像里，改了必须重新 `docker build`：
```bash
docker build -t analysis-project:plan-b .
docker rm -f analysis-project-test
docker run -d --name analysis-project-test ... analysis-project:plan-b
```

### 9.5 端口冲突

容器占 18080（宿主机）→ 80（容器内 nginx）。如果 18080 被占，改 `-p`：
```bash
docker run -p 19090:80 ...  # 用 19090
```

---

## 10. 故障排查

### 10.1 容器起不来

```bash
docker logs analysis-project-test
```

常见原因：
- `mvn package` 失败：看 `[ERROR]` 行，通常是 pom.xml 依赖问题
- `npm run build` 失败：看 vite 报错，通常是 TypeScript 类型错误
- Spring Boot 起不来：看 `/var/log/backend.log`（`docker exec analysis-project-test cat /var/log/backend.log`）

### 10.2 健康检查 502

nginx 起来了但后端没起来（180s 超时）：
```bash
docker exec analysis-project-test tail -50 /var/log/backend.log
```

常见原因：
- 远程数据源（MySQL/ClickHouse/openGauss）连不上（dev profile 配的远程地址）
- LLM_API_KEY / LLM_API_URL / LLM_MODEL 环境变量没设

### 10.3 前端白屏

```bash
curl -s http://localhost:18080/ | head -20
docker exec analysis-project-test ls /usr/share/nginx/html/
```

如果 html 目录空，说明 `npm run build` 失败或 `cp dist` 没执行。看容器日志。

### 10.4 改了代码但页面没变

- 确认 `docker restart` 执行了（`docker ps` 看 STATUS 的 Up 时间）
- 确认等够了 ~70s（健康检查返回 UP）
- 浏览器强刷（Ctrl+Shift+R）清缓存

### 10.5 maven 离线模式报缺依赖

entrypoint 用 mvn -o（offline），只用 .m2 缓存。如果缓存里缺依赖（比如 pom.xml 加了新依赖），mvn 直接报错，不会卡超时。解决：
```bash
# 在有网机器上重新 build
docker build -t analysis-project:plan-b .
# 导出 → 传内网 → load
```

---

## 11. 一键脚本（可选）

把更新流程封装成脚本 `dev-restart.sh`：

```bash
#!/bin/bash
# 改完前后端代码后跑这个
set -e
echo "[dev-restart] restarting container..."
docker restart analysis-project-test
echo "[dev-restart] waiting for health..."
for i in $(seq 1 120); do
    if curl -sf http://localhost:18080/actuator/health >/dev/null 2>&1; then
        echo "[dev-restart] READY after ${i}s"
        exit 0
    fi
    sleep 1
done
echo "[dev-restart] TIMEOUT 120s"
exit 1
```

---

## 12. 附录：完整 docker run 命令

```bash
# 停旧容器（如果有）
docker rm -f analysis-project-test 2>/dev/null

# 启动
docker run -d --name analysis-project-test \
    -p 18080:80 \
    -e SPRING_PROFILES_ACTIVE=dev,docker \
    -v /java/analysis-project/analysis-project:/app \
    analysis-project:plan-b

# 等就绪
for i in $(seq 1 120); do
    if curl -sf http://localhost:18080/actuator/health >/dev/null 2>&1; then
        echo "READY after ${i}s"
        break
    fi
    sleep 1
done

# 验证
curl http://localhost:18080/actuator/health
curl -s http://localhost:18080/ | grep -o '<title>[^<]*</title>'
```
