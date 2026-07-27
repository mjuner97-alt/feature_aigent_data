# ============ 阶段1: 构建 ============
FROM gh-proxy.org/docker/node:20-alpine AS builder

WORKDIR /app

# 先拷贝依赖描述，利用 Docker 缓存层
COPY package.json package-lock.json ./
RUN npm install

# 拷贝源码
COPY . .

# 构建产物输出到 /app/dist（覆盖 vite.config.ts 的 outDir）
ENV VITE_OUT_DIR=dist
RUN npm run build

# ============ 阶段2: 运行 ============
FROM gh-proxy.org/docker/nginx:alpine

# 删除默认配置
RUN rm /etc/nginx/conf.d/default.conf

# 放入自定义 nginx 配置
COPY nginx.conf /etc/nginx/conf.d/

# 从构建阶段拷贝静态文件
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
