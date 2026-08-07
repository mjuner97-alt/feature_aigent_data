#!/bin/bash
# ============================================================================
# redeploy.sh - 一键重新打包 + 重启容器
# ----------------------------------------------------------------------------
# 适用场景: 改了 Java 源码后, 在服务器上一键重新部署
#
# 流程:
#   1. mvn package -DskipTests  (宿主机上打 jar, 产出到 target/)
#   2. docker restart           (容器重启, entrypoint.sh 从 bind mount 的 target/ 拿新 jar)
#   3. 等待健康检查              (最长 180s)
#
# 用法:
#   ./redeploy.sh                     # 默认当前目录 + 容器名 analysis-project-test + 端口 18080
#   ./redeploy.sh /java/analysis-project/analysis-project analysis-project-test 18080
#
# 前提:
#   - 容器已通过 intranet-deploy.sh 首次部署
#   - 宿主机有 mvn + JDK (镜像里的 .m2 缓存已 bind mount 或宿主机有缓存)
# ============================================================================
set -e

SRC_DIR="${1:-$(pwd)}"
CONTAINER_NAME="${2:-analysis-project-test}"
HOST_PORT="${3:-18080}"
JAR="$SRC_DIR/target/analysis-project-0.0.1-SNAPSHOT.jar"

echo "[redeploy] 源码目录: $SRC_DIR"
echo "[redeploy] 容器名:   $CONTAINER_NAME"
echo "[redeploy] 端口:     $HOST_PORT"

# ----------------------------------------------------------------------------
# 0. 前提检查
# ----------------------------------------------------------------------------
if [ ! -f "$SRC_DIR/pom.xml" ]; then
    echo "[redeploy] ERROR: $SRC_DIR 下没有 pom.xml"
    exit 1
fi
if ! docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "[redeploy] ERROR: 容器 $CONTAINER_NAME 不存在, 先跑 ./intranet-deploy.sh 首次部署"
    exit 1
fi

# ----------------------------------------------------------------------------
# 1. mvn package
# ----------------------------------------------------------------------------
echo "[redeploy] === 1/3 mvn package ==="
cd "$SRC_DIR"
JAR_OLD_MTIME=$(stat -c %Y "$JAR" 2>/dev/null || echo 0)

# -o: offline (用 .m2 缓存, 内网无 maven central); -B: batch mode; -DskipTests: 跳过测试
mvn -B -o package -DskipTests 2>&1 | tail -20

if [ ! -f "$JAR" ]; then
    echo "[redeploy] ERROR: mvn package 后 jar 不存在: $JAR"
    exit 1
fi
JAR_NEW_MTIME=$(stat -c %Y "$JAR")
JAR_SIZE=$(stat -c %s "$JAR")
if [ "$JAR_NEW_MTIME" = "$JAR_OLD_MTIME" ]; then
    echo "[redeploy] WARNING: jar mtime 未变, 可能 mvn 没重新编译 (检查是否需要 mvn clean)"
else
    echo "[redeploy] jar 已更新: $JAR ($JAR_SIZE bytes, mtime=$(stat -c %y "$JAR"))"
fi

# ----------------------------------------------------------------------------
# 2. docker restart
# ----------------------------------------------------------------------------
echo "[redeploy] === 2/3 docker restart ==="
docker restart "$CONTAINER_NAME"
echo "[redeploy] 容器已重启, 等待 entrypoint + spring boot 启动..."

# ----------------------------------------------------------------------------
# 3. 等待健康检查
# ----------------------------------------------------------------------------
echo "[redeploy] === 3/3 等待健康检查 (最长 180s) ==="
for i in $(seq 1 180); do
    if curl -sf "http://localhost:$HOST_PORT/actuator/health" >/dev/null 2>&1; then
        echo "[redeploy] READY after ${i}s"
        echo "[redeploy] 健康检查: $(curl -s "http://localhost:$HOST_PORT/actuator/health")"
        echo "[redeploy] 部署成功! 访问 http://localhost:$HOST_PORT"
        exit 0
    fi
    if [ "$i" = "180" ]; then
        echo "[redeploy] TIMEOUT 180s, 容器日志最后 30 行:"
        docker logs "$CONTAINER_NAME" 2>&1 | tail -30
        exit 1
    fi
    sleep 1
done
