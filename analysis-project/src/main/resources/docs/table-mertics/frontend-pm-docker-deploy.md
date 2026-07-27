# frontend-pm Docker 容器部署指南

## 1. 架构概览

```
浏览器 → [frontend-pm 容器 :5174→80]
                │
                │  /v2/* 反向代理
                ▼
         后端 Spring Boot :8081
```

前端使用**多阶段构建**：Node 编译 → Nginx 托管静态文件，最终镜像仅 ~63MB。

> **当前部署拓扑**：后端 Spring Boot 运行在 Windows（`192.168.101.2:8081`），
> Docker 容器运行在 Linux（`192.168.101.16`），
> Nginx 通过局域网 IP 反向代理到 Windows 后端。

---

## 2. 所需镜像

| 镜像 | 用途 | 拉取命令 |
|------|------|----------|
| `node:20-alpine` | 阶段1：编译 React + Vite 项目 | `docker pull node:20-alpine` |
| `nginx:alpine` | 阶段2：托管编译产物 + 反向代理 | `docker pull nginx:alpine` |

> 国内网络如无法直连 Docker Hub，可使用镜像代理：
> ```bash
> docker pull gh-proxy.org/docker/node:20-alpine
> docker pull gh-proxy.org/docker/nginx:alpine
> ```

---

## 3. 后端服务地址配置

前端通过 Nginx 反向代理 `/v2/` 路径到后端 Spring Boot 服务。

配置文件：`frontend-pm/nginx.conf`

```nginx
location /v2/ {
    proxy_pass http://192.168.101.2:8081;
    # ... 省略 proxy_set_header
}
```

> ⚠️ **关键坑：`proxy_pass` 末尾绝对不能加 `/`**
>
> - `proxy_pass http://...:8081;` ✅ —— `/v2/ai/chat` 原样转发，命中后端 `V2ChatController`，SSE 带 `event:text_block_delta` 行，前端可解析
> - `proxy_pass http://...:8081/;` ❌ —— nginx 会剥掉 `/v2/` 前缀，请求变成 `/ai/chat`，命中 `ChatController`，SSE 无 `event:` 行，**前端 chat.ts 解析不到，UI 不渲染**
>
> 本地 vite dev proxy 不重写路径所以一直能跑通；远程 nginx 一旦加 `/` 就会踩坑。
> 后端有两个并行 controller：`/ai/*`（旧版 `ThinkManagerResponseDto` 格式，无 event name）和 `/v2/ai/*`（新版 `AiChatResult` 格式，带 event name）。`frontend-pm` 只认后者。
>
> **当前配置**：后端在 Windows（`192.168.101.2`），Docker 在 Linux（`192.168.101.16`），
> Nginx 直接代理到 Windows 局域网 IP。

### 根据后端部署位置修改 `proxy_pass`

| 场景 | proxy_pass 值 | 说明 |
|------|--------------|------|
| **后端在 Windows（当前）** | `http://192.168.101.2:8081` | 写 Windows 局域网 IP，**末尾不加 `/`** |
| 后端在本机（Linux） | `http://host.docker.internal:8081` | Docker 宿主机 IP，**末尾不加 `/`** |
| 后端在本机（需加 `--add-host`） | `http://host.docker.internal:8081` | 启动时加 `--add-host=host.docker.internal:host-gateway` |
| 后端也是 Docker 容器（同网络） | `http://backend:8081` | 使用容器名，需 `--network` 互通 |

> ⚠️ **`proxy_pass` 末尾一律不带 `/`**。所有场景都适用，原因见上方红字警告。

---

## 4. 项目文件结构

```
frontend-pm/
├── Dockerfile          # 多阶段构建文件
├── nginx.conf          # Nginx 配置（SPA 路由 + 反向代理）
├── .dockerignore       # 构建排除文件
├── package.json
├── vite.config.ts
├── tsconfig.json
├── index.html
└── src/                # React 源码
```

---

## 5. 构建与启动

### 5.1 构建镜像

```bash
cd /path/to/analysis-project/frontend-pm
docker build -t frontend-pm:latest .
```

构建过程：
1. **阶段1**（node:20-alpine）：`npm install` → `npm run build`，产物输出到 `/app/dist`
2. **阶段2**（nginx:alpine）：拷贝 `/app/dist` 到 Nginx 静态目录 `/usr/share/nginx/html`

### 5.2 启动容器

```bash
# 当前配置：后端在 Windows（192.168.101.2:8081）
# nginx.conf 已写死 proxy_pass http://192.168.101.2:8081/
docker run -d \
  --name frontend-pm \
  -p 5174:80 \
  frontend-pm:latest
```

```bash
# 后端在本机（Linux 宿主机）
docker run -d \
  --name frontend-pm \
  -p 5174:80 \
  --add-host=host.docker.internal:host-gateway \
  frontend-pm:latest
```

```bash
# 后端地址变更时：修改 nginx.conf 中 proxy_pass，重新构建
# sed -i 's|192.168.101.2|新的IP|g' nginx.conf
# docker rm -f frontend-pm && docker build -t frontend-pm:latest . && docker run -d --name frontend-pm -p 5174:80 frontend-pm:latest
```

```bash
# 与后端容器在同一 Docker 网络
docker run -d \
  --name frontend-pm \
  -p 5174:80 \
  --network my-net \
  frontend-pm:latest
```

> 端口说明：容器内部 Nginx 监听 80，宿主机映射为 5174（与开发端口一致，可自行修改）。

### 5.3 验证

```bash
# 检查容器状态
docker ps --filter name=frontend-pm

# 查看日志
docker logs frontend-pm

# 测试前端页面
curl -s -o /dev/null -w "%{http_code}" http://localhost:5174

# 测试 API 代理（需后端已启动）
curl -s -o /dev/null -w "%{http_code}" http://localhost:5174/v2/
```

---

## 6. 常用运维命令

```bash
# 停止
docker stop frontend-pm

# 启动（已存在）
docker start frontend-pm

# 重启
docker restart frontend-pm

# 删除容器
docker rm -f frontend-pm

# 重新构建并启动（代码更新后）
docker rm -f frontend-pm
docker build -t frontend-pm:latest /path/to/frontend-pm
docker run -d --name frontend-pm -p 5174:80 \
  --add-host=host.docker.internal:host-gateway \
  frontend-pm:latest

# 进入容器调试
docker exec -it frontend-pm sh

# 查看容器内 Nginx 配置
docker exec frontend-pm cat /etc/nginx/conf.d/nginx.conf
```

---

## 7. 一键脚本

将以下脚本保存为 `deploy.sh` 放在 `frontend-pm/` 目录：

```bash
#!/bin/bash
set -e

IMAGE_NAME="frontend-pm"
IMAGE_TAG="latest"
CONTAINER_NAME="frontend-pm"
HOST_PORT=5174
BACKEND_HOST="192.168.101.2"
BACKEND_PORT=8081

echo "=== 构建镜像 ==="
docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .

echo "=== 停止并删除旧容器 ==="
docker rm -f ${CONTAINER_NAME} 2>/dev/null || true

echo "=== 启动新容器 ==="
docker run -d \
  --name ${CONTAINER_NAME} \
  -p ${HOST_PORT}:80 \
  ${IMAGE_NAME}:${IMAGE_TAG}

echo "=== 等待启动 ==="
sleep 2

echo "=== 容器状态 ==="
docker ps --filter name=${CONTAINER_NAME}

echo ""
echo "前端地址: http://localhost:${HOST_PORT}"
echo "API 代理: http://localhost:${HOST_PORT}/v2/ → ${BACKEND_HOST}:${BACKEND_PORT}"
```

使用方式：

```bash
chmod +x deploy.sh
./deploy.sh
```

---

## 8. 注意事项

| 项目 | 说明 |
|------|------|
| ⚠️ **proxy_pass 末尾不能加斜杠** | 加斜杠会剥掉 /v2/ 前缀导致前端不渲染。详见第 3 节红字警告 |
| **后端必须先启动** | 容器内 Nginx 代理 `/v2/` 到 Windows 后端 `192.168.101.2:8081`，后端未启动时 API 返回 502 |
| **后端端口** | 开发环境 `8081`，生产环境可能是 `8080`（见 `application-prod.properties`），需对应修改 |
| **Windows 防火墙** | Windows 可能拦截 ping（ICMP），但 HTTP 端口正常即可；如 8081 不通需在 Windows 防火墙放行 |
| **SPA 路由** | Nginx 配置了 `try_files $uri $uri/ /index.html`，刷新页面不会 404 |
| **构建产物** | Docker 构建时通过 `VITE_OUT_DIR=dist` 环境变量将产物输出到 `dist/`，不影响本地开发的 `../src/main/resources/static` 输出路径 |
| **镜像大小** | 最终镜像约 63MB（nginx:alpine ~40MB + 静态文件），构建缓存层复用 `package.json` 变更才重新 `npm install` |