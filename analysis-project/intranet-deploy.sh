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
# 可选环境变量 (LLM 配置, 不设则用 application.properties 里的默认值):
#   LLM_API_KEY  - LLM API key
#   LLM_API_URL  - LLM API base URL
#   LLM_MODEL    - LLM 模型名
# ============================================================================
set -e

SRC_DIR="${1:-$(pwd)}"
IMG_TAR="${2:-analysis-project-plan-b.tar.gz}"
CONTAINER_NAME="${3:-analysis-project-test}"
HOST_PORT="${4:-18080}"

echo "[deploy] 源码目录: $SRC_DIR"
echo "[deploy] 镜像包:  $IMG_TAR"
echo "[deploy] 容器名:  $CONTAINER_NAME"
echo "[deploy] 端口:    $HOST_PORT -> 80"

# ----------------------------------------------------------------------------
# 0. 检查前提
# ----------------------------------------------------------------------------
if [ ! -f "$IMG_TAR" ]; then
    echo "[deploy] ERROR: 镜像包不存在: $IMG_TAR"
    exit 1
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
echo "[deploy] === 1/3 加载 docker 镜像 ==="
if docker images analysis-project:plan-b --format '{{.Repository}}:{{.Tag}}' | grep -q 'analysis-project:plan-b'; then
    echo "[deploy] 镜像已存在, 跳过加载 (如需强制重新加载, 先 docker rmi analysis-project:plan-b)"
else
    echo "[deploy] 加载镜像 (可能需要几分钟)..."
    gunzip -c "$IMG_TAR" | docker load
fi
docker images analysis-project:plan-b

# ----------------------------------------------------------------------------
# 2. 启动容器
# ----------------------------------------------------------------------------
echo "[deploy] === 2/3 启动容器 ==="
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

# 构造 LLM 环境变量参数 (只在变量非空时传)
LLM_ENV=""
[ -n "$LLM_API_KEY" ] && LLM_ENV="$LLM_ENV -e LLM_API_KEY=$LLM_API_KEY"
[ -n "$LLM_API_URL" ] && LLM_ENV="$LLM_ENV -e LLM_API_URL=$LLM_API_URL"
[ -n "$LLM_MODEL" ]   && LLM_ENV="$LLM_ENV -e LLM_MODEL=$LLM_MODEL"

# --security-opt seccomp=unconfined: 老版本 Docker 的 seccomp profile 不含 clone3 系统调用
# (Linux 5.3+ 才有), JVM 创建线程会失败报 pthread_create failed (EPERM) / JAVA_HOME not defined correctly
docker run -d --name "$CONTAINER_NAME" \
    --security-opt seccomp=unconfined \
    -p "$HOST_PORT":80 \
    -e SPRING_PROFILES_ACTIVE=dev,docker \
    $LLM_ENV \
    -v "$SRC_DIR":/app \
    analysis-project:plan-b

echo "[deploy] 容器已启动, 等待健康检查 (首次构建+启动约 70-80s)..."

# ----------------------------------------------------------------------------
# 3. 等待健康检查
# ----------------------------------------------------------------------------
echo "[deploy] === 3/3 等待健康检查 ==="
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
# 4. 验证
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
echo "  3. 等 ~70s, 刷新浏览器"
