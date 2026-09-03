#!/bin/bash
# ============================================================================
# intranet-deploy.sh
# 在内网宿主机上跑, 加载镜像 + 启动容器
# ----------------------------------------------------------------------------
# 用法:
#   ./intranet-deploy.sh [源码目录] [镜像tar.gz] [容器名] [宿主机端口]
#
# 默认:
#   源码目录 = 当前目录
#   镜像包  = analysis-project-plan-b.tar.gz (当前目录下)
#   容器名  = analysis-project-test
#   端口    = 18080
#
# 可选环境变量:
#   WORKSPACE_DIR - workspace 持久化目录 (默认: $SRC_DIR/../analysis-workspace)
#                    首次部署自动 mkdir, 跨容器 recreate 保留 memory/artifacts/sessions/scripts
#   LLM_API_KEY   - LLM API key
#   LLM_API_URL   - LLM API base URL
#   LLM_MODEL     - LLM 模型名
# ============================================================================
set -e

SRC_DIR="${1:-$(pwd)}"
IMG_TAR="${2:-analysis-project-plan-b.tar.gz}"
CONTAINER_NAME="${3:-analysis-project-test}"
HOST_PORT="${4:-18080}"
WORKSPACE_DIR="/java/analysis-project/analysis-project/workspace"

echo "[deploy] 源码目录:   $SRC_DIR"
echo "[deploy] 镜像包:     $IMG_TAR"
echo "[deploy] 容器名:     $CONTAINER_NAME"
echo "[deploy] 端口:       $HOST_PORT (nginx 直接监听, --network host)"
echo "[deploy] workspace:  $WORKSPACE_DIR"

# ----------------------------------------------------------------------------
# 0. 检查前提
# ----------------------------------------------------------------------------
if [ ! -f "$IMG_TAR" ]; then
    # 镜像 tar 不存在时, 检查镜像是否已加载 (重建容器场景)
    if docker images analysis-project:plan-b --format '{{.Repository}}:{{.Tag}}' | grep -q 'analysis-project:plan-b'; then
        echo "[deploy] 镜像 tar 不存在但 analysis-project:plan-b 已加载, 跳过 tar 检查"
    else
        echo "[deploy] ERROR: 镜像包不存在: $IMG_TAR 且镜像 analysis-project:plan-b 未加载"
        exit 1
    fi
fi
if [ ! -f "$SRC_DIR/pom.xml" ]; then
    echo "[deploy] ERROR: 源码目录里没有 pom.xml, 检查 SRC_DIR=$SRC_DIR"
    exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
    echo "[deploy] ERROR: docker 未安装"
    exit 1
fi

# ----------------------------------------------------------------------------
# 1. 加载 docker 镜像
# ----------------------------------------------------------------------------
echo "[deploy] === 1/4 加载 docker 镜像 ==="
if docker images analysis-project:plan-b --format '{{.Repository}}:{{.Tag}}' | grep -q 'analysis-project:plan-b'; then
    echo "[deploy] 镜像已存在, 跳过加载 (如需强制重新加载, 先 docker rmi analysis-project:plan-b)"
else
    echo "[deploy] 加载镜像 (可能需要几分钟)..."
    gunzip -c "$IMG_TAR" | docker load
fi
docker images analysis-project:plan-b

# ----------------------------------------------------------------------------
# 2. 准备 workspace 持久化目录
# ----------------------------------------------------------------------------
echo "[deploy] === 2/4 准备 workspace 持久化目录 ==="
mkdir -p "$WORKSPACE_DIR"
WORKSPACE_DIR_ABS=$(cd "$WORKSPACE_DIR" && pwd)
echo "[deploy] workspace 目录: $WORKSPACE_DIR_ABS"

# 迁移 A: 如果旧容器存在且有 workspace 数据, 且 host 目录是空的, docker cp 出来
# (从无 bind mount 的老版本升级时, 保留 memory/artifacts/sessions)
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    OLD_WS_COUNT=$(docker exec "$CONTAINER_NAME" sh -c 'ls -A /workspace 2>/dev/null | wc -l' 2>/dev/null || echo 0)
    HOST_WS_COUNT=$(ls -A "$WORKSPACE_DIR_ABS" 2>/dev/null | wc -l)
    if [ "$OLD_WS_COUNT" -gt 0 ] && [ "$HOST_WS_COUNT" -eq 0 ]; then
        echo "[deploy] 迁移 A: 从旧容器 $CONTAINER_NAME 复制 /workspace 到 $WORKSPACE_DIR_ABS ..."
        docker cp "$CONTAINER_NAME":/workspace/. "$WORKSPACE_DIR_ABS"/
        echo "[deploy] 迁移 A 完成"
    fi
fi

# 迁移 B: workspace.path 从 /workspace/harness-a2a 收敛到 /workspace 后,
# 旧结构的 $WORKSPACE_DIR_ABS/harness-a2a/* 需要提升一层到 $WORKSPACE_DIR_ABS/
# (同名目录已存在时跳过该目录, 不覆盖; 全部移完且目录空则删除空的 harness-a2a/)
LEGACY_WS_SUB="$WORKSPACE_DIR_ABS/harness-a2a"
if [ -d "$LEGACY_WS_SUB" ]; then
    echo "[deploy] 迁移 B: $LEGACY_WS_SUB/* 提升到 $WORKSPACE_DIR_ABS/ ..."
    for item in "$LEGACY_WS_SUB"/* "$LEGACY_WS_SUB"/.[!.]*; do
        [ -e "$item" ] || continue
        base=$(basename "$item")
        if [ -e "$WORKSPACE_DIR_ABS/$base" ]; then
            echo "[deploy]   跳过(已存在): $base"
        else
            mv "$item" "$WORKSPACE_DIR_ABS/"
        fi
    done
    rmdir "$LEGACY_WS_SUB" 2>/dev/null \
        || echo "[deploy]   提示: harness-a2a/ 未清空(有同名跳过的目录), 保留原目录"
fi

# ----------------------------------------------------------------------------
# 3. 启动容器
# ----------------------------------------------------------------------------
echo "[deploy] === 3/4 启动容器 ==="
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

# 构造 LLM 环境变量参数 (只在变量非空时传)
LLM_ENV=""
[ -n "$LLM_API_KEY" ] && LLM_ENV="$LLM_ENV -e LLM_API_KEY=$LLM_API_KEY"
[ -n "$LLM_API_URL" ] && LLM_ENV="$LLM_ENV -e LLM_API_URL=$LLM_API_URL"
[ -n "$LLM_MODEL" ]   && LLM_ENV="$LLM_ENV -e LLM_MODEL=$LLM_MODEL"

# --security-opt seccomp=unconfined: 老版本 Docker 的 seccomp profile 不含 clone3 系统调用
# (Linux 5.3+ 才有), JVM 创建线程会失败报 pthread_create failed (EPERM) / JAVA_HOME not defined correctly
#
# --network host: 容器直接共享宿主机网络栈, 不用 -p 端口映射.
#   原因: 默认 bridge 网络的容器无法访问宿主机 published port (如 opengauss 的 5432),
#   改用 host 网络后, dev profile 配的 192.168.101.16:5432 / 127.0.0.1:5432 都能直达.
#   nginx.conf 里 listen 18080 直接绑宿主机 18080 端口.
#
# 三个 bind mount:
#   $SRC_DIR -> /app                       源码 + prebuilt jar + entrypoint.sh
#   $SRC_DIR/nginx.conf -> /etc/nginx/conf.d/default.conf  覆盖镜像里的 nginx 配置 (改 listen 端口)
#   $WORKSPACE_DIR_ABS -> /workspace       持久化 workspace (memory/artifacts/sessions/scripts)
#                                          注意: 容器内 workspace.path 即 /workspace (docker profile 配置),
#                                          与本挂载点一一对齐, 无 harness-a2a 子层
docker run -d --name "$CONTAINER_NAME" \
    --security-opt seccomp=unconfined \
    --network host \
    -e SPRING_PROFILES_ACTIVE=dev,docker \
    $LLM_ENV \
    -v "$SRC_DIR":/app \
    -v "$SRC_DIR/nginx.conf":/etc/nginx/conf.d/default.conf:ro \
    -v "$WORKSPACE_DIR_ABS":/workspace \
    analysis-project:plan-b

echo "[deploy] 容器已启动, 等待健康检查 (jar 直跑模式约 35-40s, fallback mvn 约 70-80s)..."

# ----------------------------------------------------------------------------
# 4. 等待健康检查
# ----------------------------------------------------------------------------
echo "[deploy] === 4/4 等待健康检查 ==="
for i in $(seq 1 180); do
    if curl -sf "http://localhost:$HOST_PORT/actuator/health" >/dev/null 2>&1; then
        echo "[deploy] READY after ${i}s"
        break
    fi
    if [ "$i" = "180" ]; then
        echo "[deploy] TIMEOUT 180s, 容器日志最后 30 行:"
        docker logs "$CONTAINER_NAME" 2>&1 | tail -30
        exit 1
    fi
    sleep 1
done

# ----------------------------------------------------------------------------
# 5. 验证
# ----------------------------------------------------------------------------
echo "[deploy] === 验证 ==="
echo "健康检查:"
curl -s "http://localhost:$HOST_PORT/actuator/health"
echo
echo "前端首页:"
curl -s "http://localhost:$HOST_PORT/" | grep -o '<title>[^<]*</title>'
echo
echo "[deploy] 部署成功! 访问 http://localhost:$HOST_PORT"
echo ""
echo "[deploy] 后续更新流程:"
echo "  1. 改源码 (在 $SRC_DIR 下)"
echo "  2. docker restart $CONTAINER_NAME"
echo "  3. 等 ~40s, 刷新浏览器"
echo ""
echo "[deploy] 注意事项:"
echo "  - workspace 持久化在 $WORKSPACE_DIR_ABS (跨容器 recreate 保留)"
echo "  - 改 workspace/scripts/*.py 后需手动 cp 到 $WORKSPACE_DIR_ABS/scripts/"
echo "    (scripts/ 是 seeded once, WorkspaceMaterializer 不覆盖已有文件)"
echo "  - AGENTS.md / agent-subagents/ / skills/ / knowledge/ 每次 startup 从 jar overwrite"
