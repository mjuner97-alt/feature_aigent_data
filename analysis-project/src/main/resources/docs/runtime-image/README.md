# P1-A 容器侧脚手架 — 部署与启动指南

> 用途:把这两份文件落进 `deepanalyze-vllm:latest` 镜像(或运行中的容器),让容器内常驻一个
> FastAPI 进程,JVM 通过 SSH tunnel 走 HTTP 调用代替每次 `docker exec python3 -`。

## 1. 目录结构

本目录(`src/main/resources/docs/runtime-image/`)是一份**源码副本**,实际生效需要把
两份文件复制进容器:

| 仓库路径(本目录下) | 容器内目标路径 | 角色 |
|---|---|---|
| `server.py` | `/opt/runner/server.py` | FastAPI 主入口 |
| `python-exec.conf` | `/etc/supervisor/conf.d/python-exec.conf` | supervisord 进程守护 |

## 2. 镜像/容器先决条件

### 2.1 已经在用的共享容器(运维提前 `docker run -d`)

JVM 这边 `harness.a2a.sandbox.shared-container-name=harness-a2a-shared` 指向的容器。
检查并补齐:

```bash
# 进入容器
docker exec -it harness-a2a-shared bash

# 必须有这些(没有就 apt-get / pip install)
which supervisord    # /usr/bin/supervisord
which python3        # /usr/local/bin/python3  (>=3.10,3.12+ 更佳)
python3 -c "import fastapi, uvicorn, pandas, numpy; print('deps ok')"
```

### 2.2 缺少依赖时一次性装好

```bash
# 容器内,以 root 身份
apt-get update && apt-get install -y supervisor
pip install --no-cache-dir fastapi uvicorn[standard] pandas numpy openpyxl pydantic
mkdir -p /opt/runner /var/log/python-exec
```

## 3. 把两份文件放进容器

从仓库根目录跑(假设容器叫 `harness-a2a-shared`,Docker daemon 在远端用 SSH):

```bash
# 法 1:本地有 docker(或经 docker context)
docker cp src/main/resources/docs/runtime-image/server.py \
          harness-a2a-shared:/opt/runner/server.py
docker cp src/main/resources/docs/runtime-image/python-exec.conf \
          harness-a2a-shared:/etc/supervisor/conf.d/python-exec.conf

# 法 2:远端 Docker daemon(走 SSH)
ssh docker-host "mkdir -p /opt/runner /etc/supervisor/conf.d /var/log/python-exec"
scp src/main/resources/docs/runtime-image/server.py docker-host:/tmp/server.py
scp src/main/resources/docs/runtime-image/python-exec.conf docker-host:/tmp/python-exec.conf
ssh docker-host docker cp /tmp/server.py harness-a2a-shared:/opt/runner/server.py
ssh docker-host docker cp /tmp/python-exec.conf harness-a2a-shared:/etc/supervisor/conf.d/python-exec.conf
```

## 4. 启动 supervisord & 让 python-exec 跑起来

```bash
docker exec harness-a2a-shared bash -c '
  # 第一次:启 supervisord(已在跑就跳过)
  pgrep -f "supervisord -c" >/dev/null \
    || supervisord -c /etc/supervisor/supervisord.conf

  # 让它认识新装的 python-exec.conf
  supervisorctl reread
  supervisorctl update
  supervisorctl start python-exec

  # 看跑起来了没
  supervisorctl status python-exec
'
```

预期输出:

```
python-exec                      RUNNING   pid 12345, uptime 0:00:03
```

## 5. 容器内自检

```bash
docker exec harness-a2a-shared bash -c '
  curl -s http://127.0.0.1:8000/healthz
  echo
  curl -s -X POST http://127.0.0.1:8000/exec \
    -H "Content-Type: application/json" \
    -d "{\"code\":\"print(1+1)\"}"
'
```

预期:

```json
{"status":"ok","workers":4}
{"exit":0,"elapsed_ms":42,"stdout":"2\n","stderr":"","truncated":false}
```

## 6. 从 JVM(宿主机)端联通 — 走 SSH tunnel

```bash
# Windows: Git Bash 的 ssh(已配 ControlMaster,见 docs/ssh.md)
# Linux/Mac: 系统 ssh
ssh -fNT -L 18000:127.0.0.1:8000 docker-host

# 然后从宿主机/JVM 端:
curl -s http://127.0.0.1:18000/healthz
```

## 7. 配置 JVM 端开关(等 PythonExecHttpClient 实施后)

```properties
# application.properties
harness.a2a.python-exec.http.enabled=true
harness.a2a.python-exec.http.url=http://127.0.0.1:18000
harness.a2a.python-exec.http.fallback-on-failure=true
```

> ⚠️ 现阶段 JVM 侧的 `PythonExecHttpClient` 尚未实施,所以打开 `enabled=true` 不会生效。
> 等 Sprint 3 后续任务把客户端补上后,这个开关才有意义。在此之前,server.py 跑起来不影响
> 现有 `python_exec`(走 ssh+docker exec)继续可用。

## 8. 排错速查

| 现象 | 可能原因 | 处理 |
|---|---|---|
| `supervisorctl start python-exec` 失败 | python3/fastapi 没装 | `pip install fastapi uvicorn pandas numpy` |
| `/healthz` 404 | server.py 没跑或端口被占 | `ss -tlnp \| grep 8000` 看端口 |
| `/exec` 504 timeout | 用户代码死循环 | 正常,服务端会强杀 worker;下次请求新 fork |
| `pd.read_csv` 报 PermissionError | csv_allowed_paths 没传或路径不对 | JVM 端确保把 artifact 路径前缀加进白名单 |
| supervisord 反复重启 python-exec | 看 `/var/log/python-exec/stderr.log` | 一般是依赖版本不对 |

## 9. 关闭/卸载

```bash
docker exec harness-a2a-shared bash -c '
  supervisorctl stop python-exec
  rm /etc/supervisor/conf.d/python-exec.conf
  supervisorctl reread && supervisorctl update
'
```
