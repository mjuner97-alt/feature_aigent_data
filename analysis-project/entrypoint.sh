#!/bin/bash
set -e

echo "[entrypoint] starting analysis-project (方案 B: 容器内构建)..."

cd /app

# ============================================================================
# 1. 软链 node_modules (用镜像里预填的 /opt/node_modules, 避免内网重新下载)
# ============================================================================
FE_NM=/app/frontend-pm/node_modules
if [ ! -L "$FE_NM" ]; then
    if [ -e "$FE_NM" ]; then
        echo "[entrypoint] removing existing node_modules (replacing with symlink to /opt/node_modules)..."
        rm -rf "$FE_NM"
    fi
    ln -s /opt/node_modules "$FE_NM"
    echo "[entrypoint] symlinked frontend-pm/node_modules -> /opt/node_modules"
fi

# ============================================================================
# 2. 构建后端 (mvn package)
#    -o 离线模式: 只用 .m2 缓存, 不查远程仓库
#    内网无外网时避免每个依赖超时 30s; 缓存里缺依赖时 fail fast
# ============================================================================
echo "[entrypoint] mvn package (offline)..."
mvn -B -o package -DskipTests 2>&1 | tail -30

JAR=/app/target/analysis-project-0.0.1-SNAPSHOT.jar
if [ ! -f "$JAR" ]; then
    echo "[entrypoint] ERROR: jar build failed, $JAR not found"
    exit 1
fi

# ============================================================================
# 3. 构建前端 (npm run build)
# ============================================================================
echo "[entrypoint] npm run build..."
cd /app/frontend-pm
VITE_OUT_DIR=dist npm run build 2>&1 | tail -15

# 拷贝 dist 到 nginx html 根目录
echo "[entrypoint] copying dist to nginx html..."
rm -rf /usr/share/nginx/html/*
cp -r /app/frontend-pm/dist/* /usr/share/nginx/html/

# ============================================================================
# 4. 启动后端
# ============================================================================
cd /app
SPRING_PROFILES="${SPRING_PROFILES_ACTIVE:-dev,docker}"
echo "[entrypoint] launching spring boot with profiles=${SPRING_PROFILES}"
java -jar "$JAR" \
  --spring.profiles.active="${SPRING_PROFILES}" \
  > /var/log/backend.log 2>&1 &

# ============================================================================
# 5. 等后端起来 (最长 180s, 首次 mvn + spring boot 启动较慢)
# ============================================================================
echo "[entrypoint] waiting for backend health..."
for i in $(seq 1 180); do
  if curl -sf http://127.0.0.1:8081/actuator/health > /dev/null 2>&1; then
    echo "[entrypoint] backend ready after ${i}s"
    break
  fi
  if [ "$i" = "180" ]; then
    echo "[entrypoint] WARNING: backend not ready after 180s, nginx will return 502"
    echo "[entrypoint] last 30 lines of backend.log:"
    tail -30 /var/log/backend.log 2>/dev/null || true
  fi
  sleep 1
done

# ============================================================================
# 6. 前台运行 nginx (PID 1)
# ============================================================================
echo "[entrypoint] starting nginx on :80..."
exec nginx -g 'daemon off;'
