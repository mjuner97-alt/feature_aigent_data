# SSH 配置 — 远端 Docker 沙箱

> 适用场景:`sandbox-windows` profile,即 Windows JVM + 远端 Linux Docker daemon。
> 本文同时是 `SshHealthCheck` 启动检查未通过时的修复指引。

---

## 1. 免密登录(一次性配置)

```bash
# 1) 本机生成密钥(已有则跳过)
[ -f ~/.ssh/id_ed25519 ] || ssh-keygen -t ed25519 -N '' -f ~/.ssh/id_ed25519

# 2) 把公钥推到远端(会提示输一次密码)
ssh-copy-id root@116.148.100.114
# 远端禁了密码登录的话改成手动追加:
# cat ~/.ssh/id_ed25519.pub | ssh root@<bastion> 'cat >> ~/.ssh/authorized_keys'

# 3) 验证免密通了
ssh -o BatchMode=yes root@116.148.100.114 'echo ok && hostname'

# 4) 远端预建 artifact 根目录(harness 不会自动 mkdir 跨 mount 的根)
ssh root@116.148.100.114 'mkdir -p /opt/agentscope-workspace/harness-a2a/artifacts'
```

---

## 2. Windows 专属 — 必须使用 Git Bash 的 ssh(不要用系统自带 OpenSSH)

**这一节是 `code_interpreter` 性能优化方案 P0-C 的核心运维步骤。**
**误用系统自带 `ssh.exe` 会让 `code_interpreter` 每次任务多付 1~2 秒纯握手开销。**

### 2.1 为什么

Windows 自带的 OpenSSH(`C:\Windows\System32\OpenSSH\ssh.exe`)**不支持 `ControlMaster`** ——
每次 `ssh docker-host` 都重新做 TCP+TLS+KEX+用户认证一整套握手,稳态 ~300ms/次。
`code_interpreter` 一次复杂任务里有 4~8 次 `ssh ... docker exec`,光握手就吃掉 1.2~2.4 秒,
还没算 LLM 推理和实际计算。

Git for Windows 自带的 `/usr/bin/ssh`(MSYS2 编译版)**完整支持 `ControlMaster`**,
首次握手后开 unix socket 多路复用,后续 ssh 直接走 socket = ~30ms。

### 2.2 怎么强制让 PATH 上的 ssh 是 Git Bash 这一份

打开 PowerShell(以普通用户即可):

```powershell
where.exe ssh
```

期望输出 **第一行** 是 `C:\Program Files\Git\usr\bin\ssh.exe`(或同等 Git 安装目录)。
如果第一行是 `C:\Windows\System32\OpenSSH\ssh.exe`,有 3 个修法,任选一个:

**方法 A — 调整 PATH 顺序(推荐)。** 系统属性 → 高级 → 环境变量 → 用户/系统 PATH,
把 `C:\Program Files\Git\usr\bin` 移到 `C:\Windows\System32\OpenSSH` 之前。
重启 IDE / 终端 / Spring Boot 进程才生效。

**方法 B — 屏蔽系统 ssh。**

```powershell
# 管理员 PowerShell
Get-WindowsCapability -Online | Where-Object Name -like 'OpenSSH.Client*' `
  | Remove-WindowsCapability -Online
```

**方法 C — 在 IDEA / spring-boot 进程的启动脚本里显式注入 PATH 前缀。** 不推荐,
因为 `Runtime.exec("ssh", ...)` 走的是子进程 PATH,而 Spring Boot 直接被 IDEA 拉起时
环境变量可能不一致。

### 2.3 `~/.ssh/config` — 启用 ControlMaster

`%USERPROFILE%\.ssh\config`(Git Bash 看到的就是 `~/.ssh/config`):

```ini
Host docker-host
  HostName 116.148.100.114
  User root
  IdentityFile ~/.ssh/id_ed25519
  ControlMaster auto
  ControlPath /tmp/.ssh-mux-%C
  ControlPersist 30m
  ServerAliveInterval 30
  ServerAliveCountMax 3
```

注意:

- `ControlPath` 在 Git Bash 下用 `/tmp/...` 路径就够了(Git Bash 自动映射成 `%TEMP%`)。
  **不要写 `C:/...`** —— ssh-multiplexing 走 unix socket,路径太长(> 100 字符)在
  某些 Windows 版本上会触发 `Control socket connect... No such file or directory`。
- `ControlPersist 30m`:首次连接后保持 30 分钟,这段时间内任何 `ssh docker-host` 都瞬连。

### 2.4 验证 master 起来了

```bash
ssh -O check docker-host
# 期望: Master running (pid=12345)

# 应用层验证 — Spring Boot 启动日志里 `SshHealthCheck` 应该打:
#   [ssh-health] docker-host reachable in BatchMode (~30ms)
#   [ssh-health] ControlMaster running for docker-host — every subsequent ssh handshake will be ~30ms instead of ~300ms.
```

如果 `SshHealthCheck` 打的是 WARN(ControlMaster NOT running),按上面 2.2/2.3 修。
**`SshHealthCheck` 不会让 Spring Boot 启动失败** —— 它只是告诉你今晚 code_interpreter
为啥慢,以及修法。

### 2.5 disable 健康检查(留个后门)

完全不在乎 SSH 性能、或者跑离线测试时,在 `application.properties` 里关:

```properties
# SshHealthCheck @ConditionalOnProperty 用的就是 sandbox 整组开关;
# 不想让健康检查跑但又要 sandbox 跑的话:
#harness.a2a.sandbox.ssh-health-check.enabled=false
# (未实现时此 key 无效;若需该开关,需在 SandboxProperties 内加 sshHealthCheckEnabled 字段)
```

---

## 3. 远端机的应用层环境变量(在 docker-host 上一次性 export)

只与 harness 协议有关 —— 与 ssh 本身无关,放这里只是便于一次到位:

```bash
# 远端机 root@116.148.100.114 上执行
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# 让 harness 在远端起容器
export DOCKER_HOST=ssh://docker-host

# 让 CSV artifact 走 SSH 写远端
export HARNESS_A2A_ARTIFACTS_REMOTE_ENABLED=true
export HARNESS_A2A_ARTIFACTS_SSH_TARGET=root@116.148.100.114
export HARNESS_A2A_ARTIFACTS_REMOTE_ROOT=/opt/agentscope-workspace/harness-a2a/artifacts

java -jar target/agent_init-1.0-SNAPSHOT.jar --spring.profiles.active=sandbox
```

---

## 4. 排错速查

| 现象 | 原因 | 修法 |
|---|---|---|
| `SshHealthCheck` 打 `unreachable in BatchMode` | 公钥没推 / 远端 sshd 拒绝 | `ssh-copy-id`,然后 `ssh -vvv` 看 KEX/Auth 哪段失败 |
| `SshHealthCheck` 打 `ControlMaster NOT running` (Windows) | 走的是系统 OpenSSH | 见 §2.2,改用 Git Bash 的 `/usr/bin/ssh` |
| `ssh -O check`: `mux_client_request_alive: read from master failed: Broken pipe` | master 进程崩了或被杀 | `ssh -O exit docker-host` 然后重新 `ssh docker-host 'echo ok'` 重建 |
| `bind: Address already in use` | `ControlPath` 文件残留 | `rm /tmp/.ssh-mux-*` 后重新连 |
| `Permissions 0644 for '~/.ssh/id_ed25519' are too open` | 私钥权限错 | `chmod 600 ~/.ssh/id_ed25519`(Git Bash 下生效) |
