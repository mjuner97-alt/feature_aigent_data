# 容器离线安装 bs4 依赖并回写镜像操作文档

> 日期：2026-11-02
> 背景：容器内 Python 脚本（如 `q2_1_metrics_by_dept_version.py`）执行 `from bs4 import BeautifulSoup` 报 `ModuleNotFoundError`，需要离线安装 `beautifulsoup4`，并把安装结果固化回原镜像。
>
> **执行状态：2026-11-03 已在 docker-host 全流程执行完毕，全部成功**，详见文末 §10 执行记录。

---

## 0. 离线包清单（已下载，本仓库内）

目录：`analysis-project/docs/202611/bs4_offline/`

| 文件 | 说明 |
|---|---|
| `beautifulsoup4-4.15.0-py3-none-any.whl` | bs4 主包（纯 Python，不挑平台/Python 小版本） |
| `soupsieve-3.0.4-py3-none-any.whl` | bs4 依赖（**要求 Python ≥ 3.10**，见 §2 注意事项） |
| `typing_extensions-4.16.0-py3-none-any.whl` | bs4 依赖（旧 Python 需要） |

下载命令备查（本机，走清华镜像；pypi 直连/Clash 代理均 SSL 失败）：

```bash
pip download beautifulsoup4 -d bs4_offline -i https://pypi.tuna.tsinghua.edu.cn/simple
```

若容器 Python < 3.10，需在本机重新下载降级版 soupsieve：

```bash
pip download beautifulsoup4 "soupsieve<3" -d bs4_offline -i https://pypi.tuna.tsinghua.edu.cn/simple
```

---

## 1. 确认容器与镜像名（已核实）

实际值（docker-host 116.148.125.20）：

- 容器：`analysis-project-test`（Up 7 days, healthcheck=unhealthy，为 Spring Boot `/actuator/health` 检查问题，与 bs4 无关）
- 镜像：`analysis-project:plan-b`（原 ID `9c846284fb07`，与 `plan-b-echarts` 同 ID）
- 容器 Python：**3.14.4**，pip 25.1.1（soupsieve 3.0.4 要求 ≥3.10，满足）
- Entrypoint：`/app/entrypoint.sh`，Cmd=null（commit 不传 `-c`，默认继承容器配置）

---

## 2. 传输离线包并复制进容器

本机 → 内网服务器（scp / 跳板机 / U 盘均可）：

```bash
scp -r bs4_offline <user>@<内网服务器>:/tmp/bs4_offline
```

服务器 → 容器：

```bash
docker cp /tmp/bs4_offline analysis-project-test:/tmp/bs4_offline
```

---

## 3. 容器内离线安装

```bash
docker exec analysis-project-test python3 -m pip install \
  --no-index --find-links=/tmp/bs4_offline \
  beautifulsoup4 soupsieve typing_extensions
```

验证导入：

```bash
docker exec analysis-project-test python3 -c "from bs4 import BeautifulSoup; print(BeautifulSoup('<p>x</p>','html.parser').p.text)"
# 期望输出: x
```

清理容器内临时目录（可选，避免留进镜像）：

```bash
docker exec analysis-project-test rm -rf /tmp/bs4_offline
```

---

## 4. 停止容器

```bash
docker stop analysis-project-test
```

---

## 5. 备份原镜像

用带日期的 tag 备份，**不改动原镜像数据，只是打标**：

```bash
docker tag plan-b:latest plan-b:backup-20261102
docker images | grep plan-b
# 应看到 plan-b  latest 和 plan-b  backup-20261102 两个 tag 指向同一 IMAGE ID
```

如需把备份导出成文件存档（可选，镜像较大时耗时）：

```bash
docker save -o /tmp/plan-b_backup_20261102.tar plan-b:backup-20261102
```

---

## 6. 将容器提交为原镜像名

```bash
docker commit \
  -m "add beautifulsoup4 offline install" \
  -c 'CMD ["/bin/bash"]' \
  analysis-project-test plan-b:latest
```

说明：

- `commit` 会把容器当前可写层（含新装的 bs4）固化为新镜像。
- `-c 'CMD [...]'`：如不确定原镜像的 CMD/ENTRYPOINT，**先查再带**，避免覆盖后容器起不来：

  ```bash
  docker inspect -f '{{json .Config.Cmd}} {{json .Config.Entrypoint}}' analysis-project-test
  ```

  用查出来的值替换 `-c` 参数；查不出来就干脆不带任何 `-c`/`-m`（默认继承容器配置）。
- commit 后 `docker images` 中 `plan-b:latest` 的 IMAGE ID 会变化，`backup-20261102` 仍指向旧 ID，即备份生效。

---

## 7. 重启容器并最终验证

```bash
docker start analysis-project-test
docker exec analysis-project-test python3 -c "from bs4 import BeautifulSoup; print('bs4 ok')"
```

再跑一次实际脚本链路（通过平台 script_exec 或手工 `docker exec` 跑 `q2_1_metrics_by_dept_version.py`）确认业务脚本不再报 `ModuleNotFoundError`。

---

## 8. 回退方案

若 commit 后新镜像有问题：

```bash
docker stop analysis-project-test
docker tag plan-b:backup-20261102 plan-b:latest
docker rm analysis-project-test            # 注意：容器会被删除，确认无容器内独有数据后再做
docker run -d --name analysis-project-test --network host <原 run 参数> plan-b:latest
```

> 更稳妥的回退方式是保留原 `docker run` 启动命令/`docker inspect` 的完整配置快照，重建时照抄。建议在 §1 阶段顺手导出一份：
>
> ```bash
> docker inspect analysis-project-test > /tmp/analysis-project-test.inspect.json
> ```

---

## 9. 检查清单（TL;DR）

1. [x] `pip download` 三个 wheel（已完成，见 §0）
2. [x] scp 到内网 → `docker cp` 进容器
3. [x] 容器内 `pip install --no-index --find-links` + import 验证
4. [x] `docker stop`
5. [x] `docker tag analysis-project:plan-b analysis-project:plan-b-backup-20261103`
6. [x] `docker commit analysis-project-test analysis-project:plan-b`
7. [x] `docker start` + bs4 import 验证（业务脚本验证待跑一次 q2_1）

---

## 10. 执行记录（2026-11-03，docker-host 116.148.125.20）

| 步骤 | 结果 |
|---|---|
| wheel 上传 | `scp -r bs4_offline docker-host:/tmp/bs4_offline` ✓ |
| 安装 | `pip install --no-index --find-links=/tmp/bs4_offline --break-system-packages beautifulsoup4 soupsieve typing_extensions` ✓（typing_extensions 容器内已有 4.16.0，实装 bs4-4.15.0 + soupsieve-3.0.4；Python 3.14 需 `--break-system-packages`） |
| 导入验证 | `from bs4 import BeautifulSoup` → `bs4 ok: x` ✓ |
| 清理 | 容器内 `/tmp/bs4_offline` 与服务器 `/tmp/bs4_offline` 均已删（先清后 commit，镜像内无残留） |
| 停止 + 备份 | `docker stop` ✓；`docker tag analysis-project:plan-b analysis-project:plan-b-backup-20261103` ✓ |
| commit | `docker commit analysis-project-test analysis-project:plan-b` → 新 ID `8cbcb86372c8` ✓（未传 `-c`，继承 entrypoint.sh） |
| 重启验证 | `docker start` 后 `Up (health: starting)`，`bs4 ok after restart` ✓ |

遗留注意：

1. **`plan-b-echarts` 仍指向旧镜像 ID `9c846284fb07`**，如需与 plan-b 同步：`docker tag analysis-project:plan-b analysis-project:plan-b-echarts`。
2. 容器 healthcheck（curl /actuator/health）此前就是 unhealthy，与本次操作无关；重启后 `health: starting`，等待应用拉起后自会转归。
3. pip 3.14（PEP 668 externally-managed）需加 `--break-system-packages`，后续装包同此。
