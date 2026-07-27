# frontend-pm 内网离线部署指南（方案 A：save/load 导入）

## 1. 适用场景

目标机器**没有外网**，无法 `docker pull` / `npm install`。

思路：在**有外网**的开发机上 `docker build` + `docker save` 导出成 tar 文件，拷到内网机器 `docker load` 导入后 `docker run`。

```
[外网开发机]                  [内网机器]
docker build  ─┐
docker save   ─┼── frontend-pm.tar.gz ──> docker load ──> docker run
              ─┘                          (~25MB 文件传输)
```

最终镜像 `frontend-pm:latest` 约 63MB，gzip 压缩后约 25MB，U 盘或 scp 都能传。

---

## 2. 前置准备

### 2.1 外网机器需要

- Docker（任意现代版本）
- 能访问 `gh-proxy.org`（拉 `node:20-alpine` / `nginx:alpine`）和 `registry.npmjs.org`（拉 npm 依赖）
- 项目源码 `analysis-project/frontend-pm/`（**必须**包含以下 4 个文件）：

```
frontend-pm/
├── Dockerfile          # 多阶段构建
├── nginx.conf          # 反向代理配置（proxy_pass 末尾不能加 /）
├── .dockerignore
├── package.json
├── package-lock.json
├── vite.config.ts
├── tsconfig.json
├── index.html
└── src/                # React 源码
```

> 缺 `Dockerfile` / `nginx.conf` 会导致构建失败或代理行为错误（前端不渲染）。

### 2.2 内网机器需要

- Docker（任意现代版本）
- 能访问后端 Spring Boot 服务（默认 `192.168.101.2:8081`，按实际拓扑修改 `nginx.conf`）
- 5174 端口空闲（可改）

---

## 3. 外网机器：构建并导出镜像

### 3.1 构建

```bash
cd /path/to/analysis-project/frontend-pm
docker build -t frontend-pm:latest .
```

构建过程会联网拉取：

| 步骤 | 来源 | 大小 |
|---|---|---|
| `FROM gh-proxy.org/docker/node:20-alpine` | gh-proxy.org | ~50MB |
| `RUN npm install` | registry.npmjs.org | ~200MB |
| `FROM gh-proxy.org/docker/nginx:alpine` | gh-proxy.org | ~40MB |

首次构建约 3-5 分钟（取决于网速）。**二次构建会命中缓存层**，只重新跑 `npm run build`，秒级完成。

### 3.2 验证镜像

```bash
docker images frontend-pm:latest
# REPOSITORY      TAG     IMAGE ID       CREATED         SIZE
# frontend-pm     latest  a68cc35ddffd   1 minute ago    62.6MB
```

### 3.3 导出 tar 包

```bash
# gzip 压缩，从 ~63MB 压到 ~25MB
docker save frontend-pm:latest | gzip > frontend-pm.tar.gz

ls -lh frontend-pm.tar.gz
# -rw-r--r-- 1 root root 25M  frontend-pm.tar.gz
```

**可选**：同时导出原始镜像（不压缩），方便调试：

```bash
docker save frontend-pm:latest -o frontend-pm.tar
# 不压缩 ~63MB，load 时更快
```

### 3.4 顺手备份源码（可选）

把 `frontend-pm/` 源码打包一起带走，方便内网机器后续改 `nginx.conf` 重新构建（如果内网机器后续有外网了，或想改 `proxy_pass` 指向新 IP）：

```bash
tar czf frontend-pm-src.tar.gz frontend-pm/
```

---

## 4. 传输到内网机器

任选一种：

### 4.1 scp（推荐，如果内网机器能 SSH 出去）

```bash
scp frontend-pm.tar.gz user@内网机器IP:/opt/

# 如果源码也要带
scp frontend-pm-src.tar.gz user@内网机器IP:/opt/
```

### 4.2 U 盘 / 移动硬盘

```bash
# 外网机器
cp frontend-pm.tar.gz /media/usb/

# 内网机器
cp /media/usb/frontend-pm.tar.gz /opt/
```

### 4.3 内网文件服务器（FTP / 共享盘）

略。

---

## 5. 内网机器：导入并启动

### 5.1 导入镜像

```bash
cd /opt
docker load < frontend-pm.tar.gz

# 输出类似：
# Loaded image: frontend-pm:latest
```

验证：

```bash
docker images frontend-pm:latest
# REPOSITORY      TAG     IMAGE ID       CREATED         SIZE
# frontend-pm     latest  a68cc35ddffd   10 minutes ago   62.6MB
```

> 如果 IMAGE ID 和外网机器一致，说明导入成功。

### 5.2 检查 nginx.conf 中的后端地址

如果内网拓扑与外网机器不同（后端 IP 变了），需要先改 `nginx.conf` 再重新构建。

```bash
# 方案 1：直接进容器改配置（不持久化，重启容器会丢）
docker run -d --name frontend-pm -p 5174:80 frontend-pm:latest
docker exec frontend-pm sh -c "sed -i 's|192.168.101.2|新后端IP|' /etc/nginx/conf.d/nginx.conf && nginx -s reload"

# 方案 2：在源码里改 nginx.conf，然后重新 build（需要内网机器有外网，或者用方案 B 私服）
# 见第 7 节"拓扑变更"
```

> ⚠️ `proxy_pass` 末尾**不能加 `/`**，否则会剥掉 `/v2/` 前缀导致前端不渲染。详见 `frontend-pm-docker-deploy.md` 第 3 节红字警告。

### 5.3 启动容器

```bash
docker run -d \
  --name frontend-pm \
  -p 5174:80 \
  --restart unless-stopped \
  frontend-pm:latest
```

参数说明：
- `-d` 后台运行
- `-p 5174:80` 宿主机 5174 映射到容器 80
- `--restart unless-stopped` 开机自启，除非手动 stop

### 5.4 验证

```bash
# 容器状态
docker ps --filter name=frontend-pm

# 静态页面
curl -s -o /dev/null -w "frontend: HTTP %{http_code}\n" http://localhost:5174

# API 代理（需后端已启动）
curl -s -o /dev/null -w "proxy /v2/: HTTP %{http_code}\n" http://localhost:5174/v2/actuator/health

# 测试 SSE 事件流（应返回 event:agent_start 行）
curl -sN -X POST http://localhost:5174/v2/ai/chat \
  -H "Content-Type: application/json" \
  -d '{"input":"你好","conversationId":"smoke-test-001","user_id":"alice"}' \
  --max-time 5 | head -2
```

预期输出：

```
frontend: HTTP 200
proxy /v2/: HTTP 200  (或 503，503 也算代理通了，只是后端健康检查有依赖未就绪)
event:agent_start
data:{"code":0,"lineResult":"🤖 启动智能体：..."}
```

如果看到 `event:agent_start` / `event:text_block_delta` 行说明代理正确，前端能渲染。如果只看到 `data:{"type":"think",...}` 没有 `event:` 行，说明 `proxy_pass` 末尾多了 `/`，按 5.2 方案 1 修复。

---

## 6. 浏览器访问

```
http://内网机器IP:5174/
```

---

## 7. 拓扑变更（后端 IP 变了）

### 7.1 临时改（不重新构建）

```bash
docker exec frontend-pm sh -c \
  "sed -i 's|192.168.101.2|新IP|;s|:8081|:新端口|' /etc/nginx/conf.d/nginx.conf && nginx -s reload"
```

> 重启容器（`docker restart frontend-pm`）配置仍在，但 `docker rm -f` 后再 `run` 就会丢，需要重新执行。

### 7.2 永久改（重新构建，需源码 + 外网）

如果内网机器**有外网**且**有源码**：

```bash
cd /path/to/frontend-pm
sed -i 's|192.168.101.2|新IP|' nginx.conf
docker build -t frontend-pm:latest .
docker rm -f frontend-pm
docker run -d --name frontend-pm -p 5174:80 frontend-pm:latest
```

如果内网机器**没外网**：在外网机器上改 `nginx.conf` 重新 build + save，再走第 3-5 节流程。

---

## 8. 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| `docker load` 报 `invalid reference format` | tar 文件损坏 | 重新 `docker save` |
| 容器启动后立刻退出 | 80 端口被占用，或 nginx.conf 语法错 | `docker logs frontend-pm` 看日志 |
| 浏览器能看到页面但 API 502 | 后端没启动，或 `nginx.conf` 里后端 IP/端口不对 | 检查后端是否监听 `0.0.0.0:8081`，防火墙是否放行 |
| SSE 流有 data 但前端不渲染 | `proxy_pass` 末尾加了 `/`，请求路径被剥成 `/ai/chat` | 进容器改 nginx.conf 去掉末尾 `/` 后 `nginx -s reload` |
| 想改前端代码 | 内网机器没外网，没法 `npm install` | 在外网机器改完源码重新走第 3 节流程 |
| 镜像太大想瘦身 | nginx:alpine 已经够小（~63MB），node 阶段不进最终镜像 | 当前 Dockerfile 已是多阶段构建，无需优化 |

---

## 9. 一键脚本

把以下脚本放到外网机器的 `frontend-pm/` 目录下，命名为 `build-and-export.sh`：

```bash
#!/bin/bash
set -e

IMAGE_NAME="frontend-pm"
IMAGE_TAG="latest"
TAR_NAME="frontend-pm.tar.gz"

echo "=== 构建镜像 ==="
docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .

echo "=== 导出 tar 包 ==="
docker save ${IMAGE_NAME}:${IMAGE_TAG} | gzip > ${TAR_NAME}

echo "=== 完成 ==="
ls -lh ${TAR_NAME}
echo ""
echo "把 ${TAR_NAME} 拷到内网机器后执行："
echo "  docker load < ${TAR_NAME}"
echo "  docker run -d --name frontend-pm -p 5174:80 --restart unless-stopped ${IMAGE_NAME}:${IMAGE_TAG}"
```

使用：

```bash
chmod +x build-and-export.sh
./build-and-export.sh
```

---

## 10. 注意事项

| 项目 | 说明 |
|---|---|
| **基础镜像** | `node:20-alpine` 和 `nginx:alpine` 走 `gh-proxy.org` 代理拉取。如果外网机器能直连 Docker Hub，可把 Dockerfile 里 `gh-proxy.org/docker/` 前缀去掉 |
| **构建机架构** | x86_64 机器构建的镜像在 ARM 机器上跑不了。如果内网机器架构不同（如鲲鹏 / 飞腾 ARM），需在对应架构的外网机器上构建，或用 `docker buildx` 多架构构建 |
| **镜像版本管理** | 建议给镜像打 git commit tag：`docker tag frontend-pm:latest frontend-pm:$(git rev-parse --short HEAD)`，方便回滚 |
| **后端先启动** | 启动前端容器前确保后端 Spring Boot 已在监听 `0.0.0.0:8081`，否则 API 返回 502 |
| **防火墙** | 内网机器防火墙需放行 5174 入站；后端机器防火墙需放行 8081 入站（来自 frontend-pm 容器所在机器） |
| **HTTPS** | nginx.conf 当前是 HTTP。如果内网要求 HTTPS，需自行加证书 + 443 监听，或在前面挂个反向代理（如内网 nginx / Traefik） |
