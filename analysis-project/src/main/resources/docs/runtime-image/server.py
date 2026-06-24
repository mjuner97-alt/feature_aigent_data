"""
P1-A 容器侧常驻 Python 执行服务
================================
落到容器内路径: /opt/runner/server.py
进程守护:       /etc/supervisor/conf.d/python-exec.conf (见同目录)
监听端口:       127.0.0.1:8000 (只在容器内访问;JVM 经 SSH tunnel 转发)
契约:           见 docs/python-exec-http-service.md §3

设计要点:
* 主进程 import pandas/numpy 一次,fork 子 worker 复用已加载的模块 → 省冷启动
* 每次 /exec 跑在新 fork 的子进程里,跑完即 recycle → 跨用户 globals 不串
* 子进程内对 builtins.open / pd.read_csv 做路径白名单包装 → P2-B 第一道防线
* stdout/stderr 各截 64KB → 防止日志爆显存
* 单次 timeout 默认 60s,LLM 可调,服务端硬 cap 300s
"""

from __future__ import annotations

import builtins
import concurrent.futures
import contextlib
import io
import json
import logging
import multiprocessing
import os
import sys
import time
import traceback
from typing import Any, List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

# -----------------------------------------------------------------------------
# 配置 — 容器内可通过环境变量覆盖
# -----------------------------------------------------------------------------
WORKERS = int(os.getenv("PYTHON_EXEC_WORKERS", "4"))
HARD_MAX_TIMEOUT_S = int(os.getenv("PYTHON_EXEC_MAX_TIMEOUT", "300"))
DEFAULT_TIMEOUT_S = int(os.getenv("PYTHON_EXEC_DEFAULT_TIMEOUT", "60"))
MAX_OUTPUT_BYTES = int(os.getenv("PYTHON_EXEC_MAX_OUTPUT_BYTES", "64000"))

LOG_FMT = '{"ts":"%(asctime)s","lvl":"%(levelname)s","msg":%(message)s}'
logging.basicConfig(level=logging.INFO, format=LOG_FMT, stream=sys.stdout)
log = logging.getLogger("python-exec")


# -----------------------------------------------------------------------------
# Worker 初始化 — 仅在子进程启动时跑一次,把重模块加载好
# -----------------------------------------------------------------------------
def _init_worker() -> None:
    """forkserver 起 worker 时调用一次。pandas/numpy/openpyxl 都吃。"""
    # noqa 这些 import 的目的是预热,稳态请求不再付冷启动
    import pandas  # noqa: F401
    import numpy   # noqa: F401
    try:
        import openpyxl  # noqa: F401
    except ImportError:
        # 镜像没装也不阻断,只是用户 to_excel 时才会报
        pass


# -----------------------------------------------------------------------------
# 子进程内的代码执行 — 注意此函数在 worker 子进程里跑,不在主进程
# -----------------------------------------------------------------------------
def _run_user_code(code: str, allowed_paths: List[str]) -> dict:
    """
    fresh globals 每次新建 → Alice 写的 secret 不会被 Bob 看到。
    builtins.open 包装 → 拒绝读 allowed_paths 之外的文件。
    """
    # --- 路径白名单:在 builtins.open 上加 hook ---
    if allowed_paths:
        orig_open = builtins.open
        norm_allowed = [os.path.realpath(p) for p in allowed_paths]

        def guarded_open(path, *args, **kwargs):
            try:
                p = os.path.realpath(str(path))
            except Exception:
                p = str(path)
            if not any(p.startswith(ap) for ap in norm_allowed):
                raise PermissionError(
                    f"path {p!r} not in allowed_paths {norm_allowed!r}"
                )
            return orig_open(path, *args, **kwargs)

        builtins.open = guarded_open  # type: ignore[assignment]

    out_buf = io.StringIO()
    err_buf = io.StringIO()
    fresh_globals: dict[str, Any] = {"__name__": "__sandbox__"}

    try:
        with contextlib.redirect_stdout(out_buf), contextlib.redirect_stderr(err_buf):
            exec(compile(code, "<user_code>", "exec"), fresh_globals)
        return {
            "exit": 0,
            "stdout": out_buf.getvalue()[:MAX_OUTPUT_BYTES],
            "stderr": err_buf.getvalue()[:MAX_OUTPUT_BYTES],
            "truncated": (
                len(out_buf.getvalue()) > MAX_OUTPUT_BYTES
                or len(err_buf.getvalue()) > MAX_OUTPUT_BYTES
            ),
        }
    except BaseException:
        tb = traceback.format_exc()
        merged_err = (err_buf.getvalue() + tb)[:MAX_OUTPUT_BYTES]
        return {
            "exit": 1,
            "stdout": out_buf.getvalue()[:MAX_OUTPUT_BYTES],
            "stderr": merged_err,
            "truncated": (len(out_buf.getvalue()) + len(tb)) > MAX_OUTPUT_BYTES,
        }


# -----------------------------------------------------------------------------
# Pool 与生命周期
# -----------------------------------------------------------------------------
_pool: Optional[concurrent.futures.ProcessPoolExecutor] = None


def _get_pool() -> concurrent.futures.ProcessPoolExecutor:
    global _pool
    if _pool is None:
        # forkserver 而非 fork → 主进程已 import 的模块在子进程也能 import,
        # 但每次起的子 worker 都是干净的进程空间,跨用户 globals 不串。
        ctx = multiprocessing.get_context("forkserver")
        _pool = concurrent.futures.ProcessPoolExecutor(
            max_workers=WORKERS,
            mp_context=ctx,
            initializer=_init_worker,
        )
    return _pool


def _kill_pool() -> None:
    global _pool
    if _pool is not None:
        _pool.shutdown(wait=False, cancel_futures=True)
        _pool = None


# -----------------------------------------------------------------------------
# HTTP 层
# -----------------------------------------------------------------------------
app = FastAPI(title="python-exec", version="1.0.0")


class ExecRequest(BaseModel):
    code: str = Field(..., description="待执行 Python 源代码,UTF-8")
    timeout_seconds: int = Field(
        DEFAULT_TIMEOUT_S,
        ge=1,
        le=HARD_MAX_TIMEOUT_S,
        description=f"单次最长执行秒数,服务端硬上限 {HARD_MAX_TIMEOUT_S}s",
    )
    csv_allowed_paths: List[str] = Field(
        default_factory=list,
        description="builtins.open 允许的路径前缀列表;空 = 不做路径限制(仅推荐内网/测试)",
    )
    request_id: Optional[str] = Field(None, description="JVM 传来的 traceId,日志关联用")


class ExecResponse(BaseModel):
    exit: int
    elapsed_ms: int
    stdout: str
    stderr: str
    truncated: bool


@app.post("/exec", response_model=ExecResponse)
def exec_endpoint(req: ExecRequest) -> ExecResponse:
    rid = req.request_id or "-"
    start = time.monotonic()
    log.info(json.dumps({
        "event": "exec_start",
        "request_id": rid,
        "code_len": len(req.code),
        "timeout": req.timeout_seconds,
        "allowed_paths": req.csv_allowed_paths,
    }))

    pool = _get_pool()
    fut = pool.submit(_run_user_code, req.code, req.csv_allowed_paths)
    try:
        result = fut.result(timeout=req.timeout_seconds)
    except concurrent.futures.TimeoutError:
        fut.cancel()
        # 超时时强杀 pool 并补一个,确保下一次请求拿到干净 worker
        _kill_pool()
        elapsed_ms = int((time.monotonic() - start) * 1000)
        log.warning(json.dumps({
            "event": "exec_timeout",
            "request_id": rid,
            "elapsed_ms": elapsed_ms,
        }))
        raise HTTPException(
            status_code=504,
            detail=f"execution timeout after {req.timeout_seconds}s",
        )
    except Exception as e:
        elapsed_ms = int((time.monotonic() - start) * 1000)
        log.error(json.dumps({
            "event": "exec_error",
            "request_id": rid,
            "elapsed_ms": elapsed_ms,
            "error": repr(e),
        }))
        raise HTTPException(status_code=500, detail=f"internal error: {e!r}")

    elapsed_ms = int((time.monotonic() - start) * 1000)
    log.info(json.dumps({
        "event": "exec_done",
        "request_id": rid,
        "elapsed_ms": elapsed_ms,
        "exit": result["exit"],
        "truncated": result["truncated"],
    }))

    return ExecResponse(
        exit=result["exit"],
        elapsed_ms=elapsed_ms,
        stdout=result["stdout"],
        stderr=result["stderr"],
        truncated=result["truncated"],
    )


@app.get("/healthz")
def healthz() -> dict:
    return {"status": "ok", "workers": WORKERS}


# -----------------------------------------------------------------------------
# 入口 — 由 supervisord 启动,见同目录 python-exec.conf
# 也可手工 `python3 /opt/runner/server.py` 调试
# -----------------------------------------------------------------------------
if __name__ == "__main__":
    import uvicorn

    host = os.getenv("PYTHON_EXEC_HOST", "127.0.0.1")
    port = int(os.getenv("PYTHON_EXEC_PORT", "8000"))
    # 单 worker — Python 的多 worker 由我们自己的 ProcessPoolExecutor 管,不用 uvicorn 的
    uvicorn.run(app, host=host, port=port, workers=1, log_level="info")
