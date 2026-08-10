"""
python_exec 管道死锁诊断脚本

背景: PythonExecTool.runProcess() 在 waitFor() 期间不读 stdout/stderr,
      Windows SSH 管道缓冲 ~4KB, 输出超过就死锁 -> 超时 -> stdout 空。
      详见 memory ssh_artifactio_windows_gotchas.md (同模式已修过)。

用法: 前端输入 "用 python_exec 跑 _python_exec_pipe_diagnose.py 脚本, timeout=30s"
      修复前: 4KB+ 步骤超时, stdout 空
      修复后: 所有步骤完成, stdout 含每步时间戳 + 输出量

每步:
  1. 在 stdout 打印 marker + payload (size 字节)
  2. flush
  3. 在 stderr 同样打印一份
  4. flush
  5. 打印 elapsed

payload 用 'A' * N 拼一个 marker 行, 便于检查截断点。
"""

import sys
import time

# 测试阶梯: (label, bytes)
# 100B / 1KB / 4KB(死锁预期起点) / 8KB / 16KB
STEPS = [
    ("100B", 100),
    ("1KB", 1024),
    ("4KB", 4096),
    ("8KB", 8192),
    ("16KB", 16384),
]


def write_stream(stream, label, size):
    """写一个 marker 行 + size 字节 payload, 末尾换行。强制 flush。"""
    stream.write(f"\n=== STEP {label} size={size} BEGIN ===\n")
    payload = "A" * size + "\n"
    stream.write(payload)
    stream.write(f"=== STEP {label} size={size} END ===\n")
    stream.flush()


def main():
    t0 = time.time()
    sys.stdout.write(f"pyexec-pipe-diagnose start at {time.strftime('%H:%M:%S')}\n")
    sys.stdout.flush()

    for label, size in STEPS:
        t_step = time.time()

        # 先写 stdout
        write_stream(sys.stdout, label + "-stdout", size)
        elapsed_stdout = time.time() - t_step

        # 再写 stderr
        write_stream(sys.stderr, label + "-stderr", size)
        elapsed_total = time.time() - t_step

        # 回到 stdout 打印本步耗时 (信息流集中在 stdout 便于查看)
        sys.stdout.write(
            f"  [done] {label}: stdout+stderr elapsed={elapsed_total * 1000:.0f}ms "
            f"(stdout={elapsed_stdout * 1000:.0f}ms) "
            f"total={time.time() - t0:.2f}s\n"
        )
        sys.stdout.flush()

    sys.stdout.write(
        f"\npyexec-pipe-diagnose ALL DONE: total={time.time() - t0:.2f}s\n"
    )
    sys.stdout.flush()


if __name__ == "__main__":
    main()
