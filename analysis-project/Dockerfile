# ============================================================================
# 方案 B: 单镜像含所有构建工具 (JDK + maven + node + nginx + python3)
# ----------------------------------------------------------------------------
# Base: eclipse-temurin:17-jre (Ubuntu 26.04) + apt install openjdk-17 + maven + node 22
# 源码挂载自宿主机, 容器内跑 mvn package + npm run build
# .m2 和 node_modules 在 docker build 时预填到镜像里 (内网离线可跑)
# 改代码后: docker restart (entrypoint 重新构建 + 启动)
# ============================================================================

FROM gh-proxy.org/docker/eclipse-temurin:17-jre

# 安装 openjdk-17 (有 javac 给 maven 用) + maven + node + nginx + python3 + curl + git
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-17-crac-jdk-headless \
        maven \
        nodejs \
        npm \
        nginx \
        python3 \
        python3-pip \
        curl \
        git \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && rm -f /etc/nginx/sites-enabled/default

# 设置 JAVA_HOME 为 Ubuntu OpenJDK (有 javac, 替换掉 Temurin JRE)
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-crac-amd64
ENV PATH="$JAVA_HOME/bin:$PATH"

# 验证工具链
RUN java -version 2>&1 | head -1 \
    && javac -version \
    && mvn --version 2>&1 | head -1 \
    && node --version \
    && npm --version

# ----------------------------------------------------------------------------
# 配置国内镜像 (PyPI / Maven / npm) 加速 build 时下载
# 这些配置留在镜像里, 内网运行时如果没网, mvn/npm 会 fallback 到缓存
# ----------------------------------------------------------------------------
COPY maven-settings.xml /root/.m2/settings.xml
COPY pip.conf /etc/pip.conf
COPY npmrc /root/.npmrc

# pip 安装 agent python_exec 用到的库
RUN pip3 install --no-cache-dir --break-system-packages \
        pandas \
        sqlalchemy \
        pymysql \
        psycopg2-binary \
        clickhouse-sqlalchemy

# ----------------------------------------------------------------------------
# 预填 maven .m2 缓存: 用当前源码跑一次 mvn package
# 这样 .m2 里有完整依赖+插件, 内网离线也能 mvn package
# ----------------------------------------------------------------------------
COPY . /tmp-build
RUN cd /tmp-build \
    && mvn -B package -DskipTests 2>&1 | tail -15 \
    && rm -rf /tmp-build

# ----------------------------------------------------------------------------
# 预填 node_modules: npm ci + npm run build, 然后移到 /opt/node_modules
# entrypoint 会软链 frontend-pm/node_modules -> /opt/node_modules
# ----------------------------------------------------------------------------
COPY frontend-pm/ /tmp-build-frontend/
RUN cd /tmp-build-frontend \
    && npm ci 2>&1 | tail -5 \
    && npm run build 2>&1 | tail -5 \
    && mv node_modules /opt/node_modules \
    && rm -rf /tmp-build-frontend

# 拷贝 nginx 配置 (覆盖默认 default.conf)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 拷贝 entrypoint
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# 工作目录 (源码会挂载到这里)
WORKDIR /app
RUN mkdir -p /workspace /var/log

# 健康检查 (start-period 拉长, 首次 mvn + spring boot 启动慢)
HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=3 \
    CMD curl -sf http://localhost/actuator/health || exit 1

EXPOSE 80

ENTRYPOINT ["/entrypoint.sh"]
