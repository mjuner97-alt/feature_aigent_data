#!/bin/bash
# ============================================================================
# export-for-intranet.sh
# 在有网的 dev 机器上跑, 产出两个 tar 包用于内网部署:
#   1. analysis-project-plan-b.tar.gz  - docker 镜像 (含所有构建工具+预填缓存)
#   2. analysis-project-src.tar.gz     - 源码 (bind mount 进容器)
# ============================================================================
set -e

cd "$(dirname "$0")"

OUT_DIR="${1:-/tmp}"
echo "[export] 输出目录: $OUT_DIR"

# ----------------------------------------------------------------------------
# 1. 导出 docker 镜像
# ----------------------------------------------------------------------------
echo "[export] === 1/3 导出 docker 镜像 analysis-project:plan-b ==="
IMG_TAR="$OUT_DIR/analysis-project-plan-b.tar.gz"
docker save analysis-project:plan-b | gzip > "$IMG_TAR"
echo "[export] 镜像包: $(ls -lh "$IMG_TAR" | awk '{print $5}') $IMG_TAR"

# ----------------------------------------------------------------------------
# 2. 导出源码 (排除 build 产物、日志、IDE 文件、Vue 前端、运行时数据)
# ----------------------------------------------------------------------------
echo "[export] === 2/3 导出源码 ==="
SRC_TAR="$OUT_DIR/analysis-project-src.tar.gz"

# 先把部署脚本拷一份到当前目录 (如果还没有)
# (intranet-deploy.sh 和本脚本同目录, 直接打包)

tar czf "$SRC_TAR" \
    --exclude='target' \
    --exclude='node_modules' \
    --exclude='dist' \
    --exclude='*.log' \
    --exclude='tmp' \
    --exclude='tmp_test' \
    --exclude='.agentscope' \
    --exclude='.claude' \
    --exclude='.git' \
    --exclude='.idea' \
    --exclude='.vscode' \
    pom.xml \
    src \
    frontend-pm \
    nginx.conf \
    entrypoint.sh \
    Dockerfile \
    maven-settings.xml \
    pip.conf \
    npmrc \
    docs \
    export-for-intranet.sh \
    intranet-deploy.sh \
    2>&1 | grep -v "tar: Removing leading" || true

echo "[export] 源码包: $(ls -lh "$SRC_TAR" | awk '{print $5}') $SRC_TAR"

# ----------------------------------------------------------------------------
# 3. 校验 + 产物清单
# ----------------------------------------------------------------------------
echo "[export] === 3/3 产物清单 ==="
echo ""
echo "  镜像包: $IMG_TAR  ($(ls -lh "$IMG_TAR" | awk '{print $5}'))"
echo "  源码包: $SRC_TAR  ($(ls -lh "$SRC_TAR" | awk '{print $5}'))"
echo ""
echo "[export] 源码包内容:"
tar tzf "$SRC_TAR" | head -20
echo "  ... (共 $(tar tzf "$SRC_TAR" | wc -l) 个文件)"
echo ""
echo "[export] 下一步:"
echo "  1. 把两个 tar 包传到内网宿主机"
echo "  2. 在内网宿主机上: tar xzf analysis-project-src.tar.gz"
echo "  3. 跑部署脚本: ./intranet-deploy.sh \$(pwd) analysis-project-plan-b.tar.gz"
