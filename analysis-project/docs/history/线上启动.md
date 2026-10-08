# 内网部署 - 导出与部署指南

**日期**：2026-08-06（2026-08-19 更新：基于旧镜像离线补充前端依赖并导入内网）
**目标**：把 dev 机器上构建好的 `analysis-project:plan-b` 镜像 + 源码打包，传到内网宿主机部署。内网宿主机无需 maven/node/java 环境，只要有 Docker。

---

## 1. 概述

### 1.1 需要导出什么

| 包 | 内容 | 大小 | 说明 |
|---|------|------|------|
| `analysis-project-plan-b.tar.gz` | Docker 镜像 | ~800MB-1GB | 含 JDK 17 + maven + node 22 + nginx + python3 + jpype1 + 预填的 .m2 (161MB) + node_modules (73MB) |
| `analysis-project-src.tar.gz` | 源码 | ~8MB | Java 源码 + React 源码 + pom.xml + vite 配置 + 部署脚本 |

**不需要单独导出的**：
- ~~node_modules~~：在镜像里（`/opt/node_modules`）
- ~~.m2 缓存~~：在镜像里（`/root/.m2`）
- ~~base 镜像（eclipse-temurin:17-jre）~~：`docker save` 已包含所有层
- ~~maven/node 二进制~~：在镜像里（apt install 装的）

### 1.2 内网宿主机要求

- Docker 已安装（17.06+，支持 `docker load`）
- 能访问内网的 MySQL / ClickHouse / openGauss / LLM API（`application-dev.properties` 配的远程地址）
- **不需要**外网（mvn 用 `-o` 离线模式，npm 用镜像内缓存的 node_modules）
- 磁盘空间：镜像解压后 ~1.64GB + 源码 ~50MB + build 产物 ~200MB + workspace 持久化 ~50MB ≈ 2GB

### 1.3 workspace 持久化

容器 `/workspace/harness-a2a` 通过 bind mount 持久化到宿主机 `$WORKSPACE_DIR`（默认 `源码目录/../analysis-workspace`），跨容器 recreate 保留：

| 目录 | 持久化 | 说明 |
|---|---|---|
| `memory/` | ✅ | per-user MEMORY.md |
| `artifacts/` | ✅ | CSV 下载等 |
| `sessions/`, `user-*/` | ✅ | 运行时状态 |
| `scripts/` | ✅ | Python 脚本 (script_exec 用) |
| `agent-subagents/`, `AGENTS.md`, `knowledge/`, `skills/` | 每次 startup 从 jar overwrite | 代码产物,不需持久化 |

**从老版本升级**（无 bind mount）：脚本自动检测旧容器的 workspace 数据,`docker cp` 迁移到宿主机目录。

---

## 2. 在 dev 机器上导出

### 2.1 前提

- 已构建镜像 `analysis-project:plan-b`（见 [方案B启动文档](docker源码部署-方案B-启动文档.md) 第 5 节）
- 源码在 `/java/analysis-project/analysis-project/`
- 两个脚本在项目根目录：`export-for-intranet.sh` + `intranet-deploy.sh`

### 2.2 跑导出脚本

```bash
cd /java/analysis-project/analysis-project
./export-for-intranet.sh /tmp
```

脚本做三件事：
1. `docker save analysis-project:plan-b | gzip > /tmp/analysis-project-plan-b.tar.gz`
2. `tar czf /tmp/analysis-project-src.tar.gz` （排除 target/、node_modules、dist/、日志、Vue 前端等）
3. 打印产物清单和源码包内容

### 2.3 产物

```
/tmp/analysis-project-plan-b.tar.gz  ~800MB-1GB  (docker 镜像)
/tmp/analysis-project-src.tar.gz     ~8MB        (源码 + 部署脚本)
```

### 2.4 源码包包含什么

```
pom.xml                              # maven 构建文件
src/                                 # Java 源码 + resources
frontend-pm/
  ├── src/                           # React 源码
  ├── index.html                     # vite 入口
  ├── package.json
  ├── package-lock.json
  ├── vite.config.ts
  └── tsconfig.json
nginx.conf                           # (参考, 镜像里已烤进)
entrypoint.sh                        # (参考, 镜像里已烤进)
Dockerfile                           # (参考, 镜像已构建)
maven-settings.xml                   # (参考, 镜像里已烤进)
pip.conf                             # (参考, 镜像里已烤进)
npmrc                                # (参考, 镜像里已烤进)
docs/                                # 文档
export-for-intranet.sh               # 导出脚本 (参考)
intranet-deploy.sh                   # 部署脚本 (内网用)
```

**排除的**：
- `target/`（build 产物，容器内重新构建）
- `frontend-pm/node_modules/`（软链，镜像里有）
- `frontend-pm/dist/`（build 产物）
- `frontend/`（Vue 前端，不用）
- `io/`、`.agentscope/`（运行时数据）
- `*.log`、`tmp/`、`tmp_test/`（日志和临时文件）
- `.git/`、`.idea/`、`.vscode/`、`.claude/`（版本控制和 IDE）

---

## 3. 传输到内网

把两个 tar 包传到内网宿主机：

```bash
# 方法 1: scp (如果有 ssh 通道)
scp /tmp/analysis-project-plan-b.tar.gz user@intranet-host:/opt/
scp /tmp/analysis-project-src.tar.gz    user@intranet-host:/opt/

# 方法 2: U盘拷贝
cp /tmp/analysis-project-*.tar.gz /media/usb/

# 方法 3: 内网文件服务器
# 上传到内网 nginx/minio, 内网宿主机 wget 下载
```

---

## 4. 在内网宿主机上部署

### 4.1 解压源码

```bash
mkdir -p /opt/analysis-project
cd /opt/analysis-project
tar xzf /path/to/analysis-project-src.tar.gz
ls  # 应该看到 pom.xml, src/, frontend-pm/, intranet-deploy.sh 等
```

### 4.2 跑部署脚本

```bash
cd /opt/analysis-project
./intranet-deploy.sh "$(pwd)" /path/to/analysis-project-plan-b.tar.gz
```

**参数**（都有默认值，可省略）：
```
用法: ./intranet-deploy.sh [源码目录] [镜像tar.gz] [容器名] [宿主机端口]

默认:
  源码目录 = 当前目录
  镜像包  = analysis-project-plan-b.tar.gz
  容器名  = analysis-project-test
  端口    = 18080

可选环境变量:
  WORKSPACE_DIR - workspace 持久化目录 (默认: $SRC_DIR/../analysis-workspace)
  LLM_API_KEY   - LLM API key
  LLM_API_URL   - LLM API base URL
  LLM_MODEL     - LLM 模型名
```

脚本做四件事：
1. `gunzip -c 镜像包 | docker load`（加载镜像，~1-2min）
2. 准备 workspace 持久化目录（`mkdir -p $WORKSPACE_DIR`，从旧容器迁移数据）
3. `docker run -d -v 源码目录:/app -v $WORKSPACE_DIR:/workspace/harness-a2a analysis-project:plan-b`（启动容器，两个 bind mount）
4. 等 `curl /actuator/health` 返回 UP（jar 直跑模式 ~35-40s，fallback mvn ~70-80s）

### 4.3 自定义 LLM 和 workspace 路径（可选）

如果内网用不同的 LLM 端点或想改 workspace 存放位置，设环境变量再跑部署脚本：

```bash
# 自定义 workspace 路径 (默认在 源码目录/../analysis-workspace)
export WORKSPACE_DIR="/data/my-workspace"

# 自定义 LLM (不设则用 application.properties 里的默认值)
export LLM_API_KEY="sk-your-internal-key"
export LLM_API_URL="http://internal-llm:8080/v1"
export LLM_MODEL="your-model-name"

./intranet-deploy.sh "$(pwd)" /path/to/analysis-project-plan-b.tar.gz
```

### 4.4 验证

```bash
curl http://localhost:18080/actuator/health
# 期望: {"status":"UP","groups":["liveness","readiness"]}

curl -s http://localhost:18080/ | grep -o '<title>[^<]*</title>'
# 期望: <title>PlanNotebook + 状态机</title>
```

浏览器访问 `http://<内网宿主机IP>:18080`。

---

## 5. 内网更新流程

### 5.1 改源码（前端或后端）

```bash
cd /opt/analysis-project
vim src/main/java/com/agentscopea2a/...     # 改后端
vim frontend-pm/src/App.tsx                  # 改前端
```

### 5.2 重启容器

```bash
docker restart analysis-project-test
# jar 直跑模式 ~35-40s (npm build 7s + spring boot 32s)
# fallback mvn 模式 ~70-80s (mvn -o 43s + vite 5.6s + spring boot 32s)
```

### 5.3 等就绪

```bash
for i in $(seq 1 120); do
    if curl -sf http://localhost:18080/actuator/health >/dev/null 2>&1; then
        echo "READY after ${i}s"
        break
    fi
    sleep 1
done
```

### 5.4 改 Python 脚本 (`workspace/scripts/`)

⚠️ `scripts/` 是 "seeded once" — WorkspaceMaterializer **不会**覆盖已有文件。改源码后需手动同步到持久化目录：

```bash
# 改源码
vim src/main/resources/workspace/scripts/_gauss_jdbc.py

# 手动 cp 到 workspace 持久化目录
cp src/main/resources/workspace/scripts/_gauss_jdbc.py \
   $WORKSPACE_DIR/scripts/

# 不需重启容器 (script_exec 每次读文件, 立即生效)
# 或重启容器也可以
```

`AGENTS.md` / `agent-subagents/` / `skills/` / `knowledge/` 是 "always overwrite"，改源码后 `docker restart` 即可生效，无需手动 cp。

### 5.5 改 pom.xml 加新依赖（需要外网）

`mvn -o` 离线模式，如果新依赖不在 .m2 缓存里，直接报错。解决：

1. 在 dev 机器上改 pom.xml
2. `docker build -t analysis-project:plan-b .`（重新构建镜像，预填新依赖）
3. `./export-for-intranet.sh /tmp`（重新导出）
4. 把新的 `analysis-project-plan-b.tar.gz` 传到内网
5. 内网宿主机：`docker rmi analysis-project:plan-b`（删旧镜像）
6. `./intranet-deploy.sh "$(pwd)" /path/to/new-analysis-project-plan-b.tar.gz`

workspace 持久化目录不受影响（bind mount 独立于镜像）。

---

## 6. 故障排查

### 6.1 docker load 失败

```
Error: processing tar file(exit status 1): unexpected EOF
```

镜像包传输损坏，重新传。校验大小：
```bash
ls -l analysis-project-plan-b.tar.gz  # dev 机器和内网的文件大小应该一致
```

### 6.2 容器起不来

```bash
docker logs analysis-project-test
```

常见原因：
- `mvn -o` 报缺依赖：.m2 缓存不完整，需在 dev 机器 rebuild 镜像
- `npm run build` 报错：TypeScript 类型错误，看 vite 报错
- Spring Boot 起不来：看 `/var/log/backend.log`

### 6.3 健康检查 502

后端没起来（180s 超时）：
```bash
docker exec analysis-project-test tail -50 /var/log/backend.log
```

常见原因：
- 远程 MySQL/ClickHouse/openGauss 连不上（内网不通或地址错误）
- LLM API 连不上（LLM_API_URL 错误）

### 6.4 前端白屏

```bash
docker exec analysis-project-test ls /usr/share/nginx/html/
```

如果空，说明 `npm run build` 失败或 `cp dist` 没执行。看容器日志。

### 6.5 端口被占

```bash
# 换端口
docker rm -f analysis-project-test
./intranet-deploy.sh "$(pwd)" analysis-project-plan-b.tar.gz analysis-project-test 19090
```

### 6.6 script_exec 报 "GAUSS_JAR 环境变量未设置"

ScriptExecTool 没注入 gauss 环境变量。检查：

```bash
# 1. application-dev.properties 里 gauss 数据源 enabled
grep gauss.enabled src/main/resources/application-dev.properties
# 应为: spring.datasource.hikari.gauss.enabled=true

# 2. 后端日志看 ScriptExecTool 是否注册
docker exec analysis-project-test grep -i "scriptExec\|gauss" /var/log/backend.log | tail -10
```

### 6.7 JPype 报 "ModuleNotFoundError: No module named 'java'"

`_gauss_jdbc.py` 缺 `import jpype.imports`。检查持久化目录里的脚本：

```bash
grep 'jpype.imports' $WORKSPACE_DIR/scripts/_gauss_jdbc.py
# 应输出: import jpype.imports  # 启用 from java... import ... 语法
```

如果缺失，从源码 cp 过去：

```bash
cp src/main/resources/workspace/scripts/_gauss_jdbc.py $WORKSPACE_DIR/scripts/
```

### 6.8 workspace 数据丢失

检查 bind mount 是否生效：

```bash
docker inspect analysis-project-test --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{println}}{{end}}'
# 应看到两行:
# /opt/analysis-project -> /app
# /data/analysis-workspace -> /workspace/harness-a2a
```

如果只有 `/app` 一行，说明 `WORKSPACE_DIR` 没生效，重新跑部署脚本。

---

## 7. 完整流程速查

### Dev 机器（一次性 + 每次 pom.xml 变更）

```bash
cd /java/analysis-project/analysis-project
docker build -t analysis-project:plan-b .
./export-for-intranet.sh /tmp
# 产出: /tmp/analysis-project-plan-b.tar.gz + /tmp/analysis-project-src.tar.gz
```

### 内网宿主机（首次部署）

```bash
mkdir -p /opt/analysis-project && cd /opt/analysis-project
tar xzf /path/to/analysis-project-src.tar.gz
./intranet-deploy.sh "$(pwd)" /path/to/analysis-project-plan-b.tar.gz
# 自动: 加载镜像 + mkdir workspace + 启动容器 (两个 bind mount) + 等健康检查
# 访问 http://localhost:18080
```

### 内网宿主机（改 Java/前端代码后更新）

```bash
cd /opt/analysis-project
vim src/...  # 改代码
docker restart analysis-project-test
# 等 ~35-40s (jar 直跑模式)
```

### 内网宿主机（改 Python 脚本后更新）

```bash
cd /opt/analysis-project
vim src/main/resources/workspace/scripts/foo.py
# 手动 cp 到 workspace 持久化目录 (scripts/ 是 seeded once, 不覆盖)
cp src/main/resources/workspace/scripts/foo.py ../analysis-workspace/scripts/
# 不需重启 (script_exec 每次读文件)
```

### 内网宿主机（改 AGENTS.md / skills / agent-subagents 后更新）

```bash
cd /opt/analysis-project
vim src/main/resources/workspace/AGENTS.md
docker restart analysis-project-test
# 这些是 always overwrite, 重启自动同步
```

### 内网宿主机（pom.xml 变更后更新）

```bash
# Dev 机器上 rebuild + 重新导出 + 传镜像包
# 内网宿主机上:
docker rmi analysis-project:plan-b
./intranet-deploy.sh "$(pwd)" /path/to/new-analysis-project-plan-b.tar.gz
# workspace 持久化目录不受影响 (bind mount 独立于镜像)
```




## 8. 基于旧 `plan-b` 离线补充前端依赖

本节用于以下场景：源码中的 `frontend-pm/package.json` 已增加前端依赖，但现有
`analysis-project:plan-b` 镜像内的 `/opt/node_modules` 仍是旧版本。只执行
`docker restart` 不会更新镜像依赖。

下面的命令不会重新安装 JDK、Maven、Node 或 Python，而是以现有
`analysis-project:plan-b` 为基础，只加入 `echarts@6.1.0` 及其依赖
`zrender@6.1.0`、`tslib`。

### 8.1 检查旧镜像和 npm 离线缓存

```bash
cd /java/analysis-project/analysis-project

# 旧镜像中应显示 echarts-missing
docker run --rm --entrypoint sh analysis-project:plan-b -lc \
  'test -f /opt/node_modules/echarts/package.json \
    && echo echarts-present || echo echarts-missing'

# 内网离线构建前，缓存中必须存在这两个包
npm cache ls echarts@6.1.0
npm cache ls zrender@6.1.0
```

如果缓存中没有对应 `.tgz`，必须先在能够访问 npm registry 的机器执行一次：

```bash
npm cache add echarts@6.1.0
npm cache add zrender@6.1.0
```

然后把 npm 缓存或已经构建好的镜像带入无网络环境。不要在无缓存的内网机器直接
执行 `npm install --offline`。

### 8.2 基于旧镜像构建派生镜像

以下是本次实际使用并验证通过的完整命令：

```bash
set -eu

BUILD_DIR=$(mktemp -d /tmp/plan-b-echarts.XXXXXX)
trap 'rm -rf "$BUILD_DIR"' EXIT

# 只使用本机 npm 缓存，不访问网络。npm 会同时安装 zrender 和 tslib。
npm install \
  --prefix "$BUILD_DIR" \
  --offline \
  --ignore-scripts \
  --no-audit \
  --no-fund \
  --package-lock=false \
  echarts@6.1.0

test -f "$BUILD_DIR/node_modules/echarts/package.json"
test -f "$BUILD_DIR/node_modules/zrender/package.json"

cat > "$BUILD_DIR/Dockerfile" <<'EOF'
FROM analysis-project:plan-b
COPY node_modules/ /opt/node_modules/
EOF

docker build \
  -t analysis-project:plan-b-echarts \
  "$BUILD_DIR"
```

### 8.3 验证依赖和真实前端构建

```bash
# 验证派生镜像中的依赖版本
docker run --rm --entrypoint sh \
  analysis-project:plan-b-echarts -lc \
  'grep -m1 version /opt/node_modules/echarts/package.json && \
   grep -m1 version /opt/node_modules/zrender/package.json'

# 挂载当前源码，执行真实 TypeScript + Vite 构建
docker run --rm --entrypoint sh \
  -v /java/analysis-project/analysis-project:/app \
  analysis-project:plan-b-echarts -lc \
  'cd /app/frontend-pm && \
   VITE_OUT_DIR=/tmp/frontend-dist npm run build'
```

只有看到 `npm run build` 成功完成后，才执行标签切换：

```bash
# 先给旧镜像保留回滚标签
docker tag \
  analysis-project:plan-b \
  analysis-project:plan-b-before-echarts-20260819

# 再让正式标签指向验证通过的派生镜像
docker tag \
  analysis-project:plan-b-echarts \
  analysis-project:plan-b

docker images --format '{{.Repository}}:{{.Tag}} {{.ID}} {{.Size}}' \
  | grep '^analysis-project:plan-b'
```

此时预期：

```text
analysis-project:plan-b                            <新镜像 ID> ...
analysis-project:plan-b-echarts                    <同一个新镜像 ID> ...
analysis-project:plan-b-before-echarts-20260819    <旧镜像 ID> ...
```

### 8.4 导出本次镜像

镜像 TAR 已生成：

```text
/data/images/analysis-project-plan-b-echarts-20260819.tar
```

信息：

```text
大小：1,762,149,888 bytes，约 1.7GB
权限：644
SHA-256：
9c2608da592b1e30a4c693714a09015466dc16db17e2fcd888e11ed6107ea278
```

归档内标签已确认：

```text
analysis-project:plan-b
```

### 8.5 内网导入步骤

先把 TAR 传到内网服务器，例如：

```bash
scp /data/images/analysis-project-plan-b-echarts-20260819.tar \
  root@内网服务器:/data/images/
```

进入内网服务器后，先校验文件：

```bash
sha256sum /data/images/analysis-project-plan-b-echarts-20260819.tar
```

必须与上述 SHA-256 一致。

1. 保留内网原来的旧镜像：

```bash
docker tag \
  analysis-project:plan-b \
  analysis-project:plan-b-before-echarts-20260819
```

确认旧标签：

```bash
docker images | grep analysis-project
```

2. 导入新镜像并替换 `plan-b` 标签：

```bash
docker load \
  -i /data/images/analysis-project-plan-b-echarts-20260819.tar
```

因为 TAR 内已经包含 `analysis-project:plan-b`，导入后该标签会指向新镜像；旧镜像仍由备份标签保留。

确认两个标签：

```bash
docker images --format '{{.Repository}}:{{.Tag}} {{.ID}} {{.Size}}' \
  | grep '^analysis-project:plan-b'
```

预期两个不同镜像 ID：

```text
analysis-project:plan-b                            9c846284fb07 ...
analysis-project:plan-b-before-echarts-20260819    <旧镜像ID> ...
```

### 8.6 重建容器

仅执行 `docker restart` 不会切换到新镜像，必须删除并重新创建容器：

```bash
docker rm -f analysis-project-test

docker run -d \
  --name analysis-project-test \
  -p 18080:80 \
  -e SPRING_PROFILES_ACTIVE=dev,docker \
  -v /java/analysis-project/analysis-project:/app \
  analysis-project:plan-b
```

验证 ECharts：

```bash
docker exec analysis-project-test sh -lc \
  'grep -m1 version /opt/node_modules/echarts/package.json'
```

查看启动日志：

```bash
docker logs -f analysis-project-test
```

### 8.7 回滚

需要回滚时：

```bash
docker tag \
  analysis-project:plan-b-before-echarts-20260819 \
  analysis-project:plan-b
```

然后再次删除并重建容器。
