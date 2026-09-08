内网中，比较完整的保存日志：

15:10:08.723 DEBUG i.a.h.a.s.i.d.DockerSandbox          : [sandbox-docker] Skipping shutdown: container is user-managed:                                                    
faec82efa08b59e9c0837deb8f41ed3007fc0c9cf68e0eaa47a1b27245142966                                                                                                            
15:10:08.723 ERROR i.a.h.a.m.SandboxLifecycleMiddleware : [sandbox-mw] Failed to acquire/start sandbox                                                                      
io.agentscope.harness.agent.sandbox.SandboxException$WorkspaceStartException: Failed to start workspace at: \workspace                                                      
at io.agentscope.harness.agent.sandbox.AbstractBaseSandbox.start(AbstractBaseSandbox.java:122)                                                                              
at io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox.start(DockerSandbox.java:59)                                                                               
at io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware.acquireForCall(SandboxLifecycleMiddleware.java:84)                                                     
at io.agentscope.harness.agent.HarnessAgent.lambda$wrappedStreamEvents$22(HarnessAgent.java:846)                                                                            
at reactor.core.publisher.FluxUsing.subscribe(FluxUsing.java:75)                                                                                                            
at reactor.core.publisher.Flux.subscribe(Flux.java:8836)                                                                                                                    
at reactor.core.publisher.Flux.subscribeWith(Flux.java:8957)                                                                                                                
at reactor.core.publisher.Flux.subscribe(Flux.java:8801)                                                                                                                    
at reactor.core.publisher.Flux.subscribe(Flux.java:8725)                                                                                                                    
at com.agentscopea2a.v2.service.impl.ChatStreamServiceImpl.lambda$stream$7(ChatStreamServiceImpl.java:438)                                                                  
at reactor.core.publisher.MonoRunnable.call(MonoRunnable.java:73)                                                                                                           
at reactor.core.publisher.MonoRunnable.call(MonoRunnable.java:32)                                                                                                           
at reactor.core.publisher.FluxSubscribeOnCallable$CallableSubscribeOnSubscription.run(FluxSubscribeOnCallable.java:228)                                                     
at reactor.core.scheduler.SchedulerTask.call(SchedulerTask.java:68)                                                                                                         
at reactor.core.scheduler.SchedulerTask.call(SchedulerTask.java:28)                                                                                                         
at java.base/java.util.concurrent.FutureTask.run$$$capture(FutureTask.java:264)                                                                                             
at java.base/java.util.concurrent.FutureTask.run(FutureTask.java)                                                                                                           
at java.base/java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.run(ScheduledThreadPoolExecutor.java:304)                                                 
at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)                                                                                
at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)                                                                                
at java.base/java.lang.Thread.run(Thread.java:840)                                                                                                                          
Caused by: io.agentscope.harness.agent.sandbox.SandboxException$SandboxRuntimeException: docker tar extract failed with exit code 2: tar: AGENTS.md: time stamp 2026-09-03  
07:10:05 is 41.301506127 s in the future                                                                                                                                    
tar: agent-subagents/analyze_data.md: Cannot open: Function not implemented                                                                                                 
tar: agent-subagents/generate_skill.md: Cannot open: Function not implemented                                                                                               
tar: knowledge-dynamic/APP_KEY_KNOWLEDGE.md: Cannot open: Function not implemented                                                                                          
tar: knowledge-dynamic/APP_KNOWLEDGE.md: Cannot open: Function not implemented                                                                                              
tar: knowledge-dynamic/GROUP_KNOWLEDGE.md: Cannot open: Function not implemented                                                                                            
tar: knowledge-dynamic/ISSUE_KNOWLEDGE.md: Cannot open: Function not implemented                                                                                            
tar: knowledge-dynamic/METRIC_KNOWLEDGE.md: Cannot open: Function not implemented                                                                                           
tar: knowledge-dynamic/PRODUCT_LINE_KNOWLEDGE.md: Cannot open: Function not implemented                                                                                     
tar: knowledge-dynamic/QI_KNOWLEDGE.md: Cannot open: Function not implemented                                                                                               
tar: knowledge-dynamic/TEAM_KNOWLEDGE.md: Cannot open: Function not implemented                                                                                             
tar: knowledge-dynamic/TEST_GROUP_KNOWLEDGE.md: Cannot open: Function not implemented                                                                                       
tar: knowledge-dynamic/knowledge-index.yaml: Cannot open: Function not implemented                                                                                          
tar: knowledge/.gitkeep: Cannot open: Function not implemented                                                                                                              
tar: knowledge/KNOWLEDGE.md: Cannot open: Function not implemented                                                                                                          
tar: knowledge/metric-categories.yaml: Cannot open: Function not implemented                                                                                                
tar: Exiting with failure status due to previous errors

at io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox.doHydrateWorkspace(DockerSandbox.java:206)                                                                 
at io.agentscope.harness.agent.sandbox.AbstractBaseSandbox.applyWorkspaceProjectionIfChanged(AbstractBaseSandbox.java:279)                                                  
at io.agentscope.harness.agent.sandbox.AbstractBaseSandbox.start(AbstractBaseSandbox.java:116)                                                                              
... 20 more

● Bash(sed -n 100,135p /tmp/harness-src/io/agentscope/harness/agent/sandbox/AbstractBaseSandbox.java)
⎿                  // Workspace was not ready at last stop
if (snapshot != null && snapshot.isRestorable()) {
// Branch C: restore from snapshot + all spec entries
… +33 lines (ctrl+o to expand)

● Bash(sed -n 150,200p /tmp/harness-src/io/agentscope/harness/agent/sandbox/SandboxException.java)
⎿           */
public SandboxRuntimeException(String message, Throwable cause) {
super(SandboxErrorCode.WORKSPACE_START_ERROR, message, cause);
… +48 lines (ctrl+o to expand)

● The exception at AbstractBaseSandbox.java:122 builds the message from Path.of(state.getWorkspaceSpec().getRoot()) — printed as \workspace, which is what
Path.of("/workspace").toString() returns only on a Windows JVM. On Linux it would print /workspace. Let me check whether this error also exists in your local backend logs.

Searched for 2 patterns, listed 2 directories (ctrl+o to expand)

● Local logs are stale (last write Aug 5) and don't contain this error — so the log is indeed from the intranet backend. Now let me check how the workspace spec root is built, to
interpret the \workspace backslash.

Searched for 1 pattern, read 1 file (ctrl+o to expand)

● The spec root comes from s.getWorkspaceRoot() (the workspace-root property = /workspace). Let me check whether getWorkspaceRoot() transforms it, since \workspace (backslash)
only appears on a Windows JVM.

Searched for 6 patterns (ctrl+o to expand)

● Bash(grep -rn "projection\|Projection" /tmp/harness-src/io/agentscope/harness/agent/sandbox/AbstractBaseSandbox.java | head -20; echo ===; sed -n 250,300p /tmp/harne…)
⎿  116:            applyWorkspaceProjectionIfChanged(spec);
267:    private void applyWorkspaceProjectionIfChanged(WorkspaceSpec spec) throws Exception {
268:        WorkspaceProjectionApplier.ProjectionPayload payload =
… +45 lines (ctrl+o to expand)

● Bash(find /tmp/harness-src -name "WorkspaceProjectionApplier.java"; echo ===; sed -n 1,120p /tmp/harness-src/io/agentscope/harness/agent/sandbox/WorkspaceProjectionA…)
⎿  /tmp/harness-src/io/agentscope/harness/agent/sandbox/WorkspaceProjectionApplier.java
===
/*
… +51 lines (ctrl+o to expand)

● The tar payload is built in-memory from JVM-local files (mtimes = file modification times on the JVM machine). Let me verify on docker-host whether container faec82ef exists
there now, and compare clocks for the 41s skew.

● Bash(ssh -o BatchMode=yes -o ConnectTimeout=10 docker-host "date -u '+%Y-%m-%d %H:%M:%S UTC' && docker ps -a --no-trunc | grep -i faec ; docker ps --no-trunc --forma…)
⎿  Warning: Permanently added '116.148.120.167' (ED25519) to the list of known hosts.
2026-09-03 07:30:55 UTC
38d811e84f96bce130eee3c1a2e56cc1e45af2cd7906694c8e3153dc9a8984b2 analysis-project-test
… +21 lines (ctrl+o to expand)

● Bash(date -u '+%Y-%m-%d %H:%M:%S UTC (local)'; powershell -Command "Get-Date -Format u" 2>/dev/null)
⎿  2026-09-03 07:30:56 UTC (local)
2026-09-03 15:30:59Z

● docker-host has no faec82ef and its clock matches local (not 41s off) — so the failing container is on yet another docker daemon. Now let me check the project's shadow classes,
since the stack trace runs through io.agentscope.harness classes that the project may override locally.

Searched for 2 patterns, read 2 files (ctrl+o to expand)

● The shadowed DockerCliRunner confirms the hydrate path (ssh <target> docker exec -i <id> tar -xf - -C /workspace). Let me search for the known causes of this exact tar ENOSYS
error.

● Web Search("tar extract "Cannot open: Function not implemented" docker container ENOSYS")
⎿  Did 1 search in 19s

● Found it — this is a known ENOSYS class of failure. Let me verify the tar version inside the analysis-project-test container on docker-host and reproduce the hydrate manually
there to compare with the intranet.

● Bash(ssh -o BatchMode=yes docker-host "docker exec analysis-project-test tar --version; docker exec analysis-project-test sh -c 'cat /etc/os-release | head -2'; unam…)
⎿  Warning: Permanently added '116.148.120.167' (ED25519) to the list of known hosts.
tar (GNU tar) 1.35
Copyright (C) 2023 Free Software Foundation, Inc.
… +9 lines (ctrl+o to expand)

● The image is Ubuntu 26.04 with GNU tar 1.35 — exactly the distro where tar uses openat2(RESOLVE_BENEATH) for file creation during extraction. docker-host's kernel is 6.8
(openat2 exists), so it works there. Let me reproduce the full hydrate path against docker-host's container to confirm the transport and tar behavior work there.

● Bash(ssh -o BatchMode=yes docker-host "docker exec analysis-project-test sh -c 'ls -la /workspace/ 2>/dev/null | head -20; ls /workspace/agent-subagents /workspace/k…)
⎿  Warning: Permanently added '116.148.120.167' (ED25519) to the list of known hosts.
total 56
drwxr-xr-x 12 root root 4096 Sep  2 12:27 .
… +17 lines (ctrl+o to expand)

● /workspace on docker-host's container is fully populated — hydrate works there. Now the decisive test: simulate the intranet's old kernel/docker by blocking openat2 via seccomp,
and see if tar reproduces the exact error pattern.

● Bash(ssh -o BatchMode=yes docker-host 'cat > /tmp/no-openat2.json <<EOF
{"defaultAction":"SCMP_ACT_ALLOW","syscalls":[{"names":["openat2"],"action":"SCMP_ACT_ERRNO",…)
⎿  Warning: Permanently added '116.148.120.167' (ED25519) to the list of known hosts.

     ==========
     … +31 lines (ctrl+o to expand)

● tar succeeded even with openat2 blocked — but let me verify the seccomp profile actually applied before ruling that out.

● Bash(ssh -o BatchMode=yes docker-host 'docker run --rm --security-opt seccomp=/tmp/no-openat2.json deepanalyze-vllm:latest python3 -c "
import ctypes, errno…)
⎿  Warning: Permanently added '116.148.120.167' (ED25519) to the list of known hosts.
https://docs.nvidia.com/datacenter/cloud-native/ .

     openat2 ret= -1 errno= ENOSYS

● The seccomp block applied (openat2 → ENOSYS) yet tar extracted fine — so plain openat2 blocking doesn't reproduce it. Let me search for what returns ENOSYS specifically on file
creation.

● Web Search(""Function not implemented" open O_CREAT ENOSYS NFS CIFS bind mount docker "Cannot open" tar new file")
⎿  Did 2 searches in 35s

● The web confirms the ENOSYS is tar's openat2() usage, but my test on the current image didn't reproduce it. Let me retry more faithfully — extracting into a bind-mounted
/workspace (like the intranet container), since that's the key difference.

● Bash(ssh -o BatchMode=yes docker-host 'rm -rf /tmp/hydroot && mkdir -p /tmp/hydroot && echo pre > /tmp/hydroot/AGENTS.md
tar -C /tmp/hydsrc -cf - AGENTS.md agent-sub…)
⎿     https://docs.nvidia.com/datacenter/cloud-native/ .

     TAR_EXIT=0
     … +7 lines (ctrl+o to expand)

● The web evidence points to openat2() filtering, but this image's tar falls back gracefully. Let me test blocking the full batch of modern syscalls that an older Docker engine's
default seccomp profile would return ENOSYS for.

● Bash(ssh -o BatchMode=yes docker-host 'cat > /tmp/no-newsys.json <<EOF
{"defaultAction":"SCMP_ACT_ALLOW","syscalls":[{"names":["openat2","faccessat2","close_range","…)
⎿  WARNING: The NVIDIA Driver was not detected.  GPU functionality will not be available.
Use the NVIDIA Container Toolkit to start this container with GPU support; see
https://docs.nvidia.com/datacenter/cloud-native/ .
… +2 lines (ctrl+o to expand)

● Still passes — so docker-host's current image tar is tolerant. This isolates the failure to the intranet's own environment (its container image's tar or its kernel/docker being
older). Let me check the hydrate-enabled kill-switch wiring in the shadowed DockerSandbox for the workaround option.

Searched for 1 pattern, read 2 files (ctrl+o to expand)

● Diagnosis complete. Let me save the key findings to memory, then summarize.

Wrote 1 memory (ctrl+o to expand)

● Bash(printf '%s\n' '- [Intranet tar ENOSYS diagnosis](intranet_tar_enosys_diagnosis.md) - 2026/09/03 内网 hydrate 失败根因: 容器内 tar 用 openat2() 老内核返回 ENOSYS; 快速修复
hydrate-…)
⎿  (No output)

● 诊断结论

这个错误里有两个独立问题，只有一个致命：

1. time stamp 2026-09-03 07:10:05 is 41.3s in the future — 非致命

这是 warning 不是错误。tar 解压时发现文件 mtime 比容器时钟快了 41 秒。文件 mtime 来自后端 JVM 机器（workspace 文件刚被 materialize，mtime=15:10:05），说明内网 docker
宿主机的时钟比后端机器慢约 41 秒（没开 NTP）。修一下时间同步即可，但它不是失败的原因。

2. Cannot open: Function not implemented — 真正的失败原因

这是 open() 返回 ENOSYS，是一个已知上游问题：新版 GNU tar 在解压创建文件时使用 openat2() 系统调用（Linux 5.6 / 2020 年才加入）。当运行环境不支持或不放行这个 syscall 时，tar 就报
Cannot open: Function not implemented。证据链完全吻合：

- 顶层 AGENTS.md 成功（tar 用普通 open 打开已有文件）
- 所有子目录文件失败（agent-subagents/、knowledge-dynamic/、knowledge/ — 创建新文件走 openat2 路径）
- 这与 Fedora bug 2437037 (https://bugzilla.redhat.com/show_bug.cgi?id=2437037)（tar 升级后开始用 openat2 → mock/nspawn 里 seccomp 过滤 → 同样报错）、Colima #1613
  (https://github.com/abiosoft/colima/issues/1613)（QEMU 不支持 openat2）、docker-brew-fedora #125 (https://github.com/fedora-cloud/docker-brew-fedora/issues/125)（老 runtime + 新
  tar）是同一类问题

我在 docker-host 上做了实验验证：
- 现有 deepanalyze-vllm 镜像（Ubuntu 26.04, tar 1.35）即使把 openat2 用 seccomp 封掉（确认返回 ENOSYS），tar 解压嵌套目录依然正常 — 这个版本的 tar 有回退逻辑
- 内网的容器 faec82ef 不在 docker-host 上（docker-host 的 analysis-project-test 是 38d811e84f96），时钟也不差 41 秒 → 失败发生在内网自己的 docker 环境里

结论：内网那台 docker 宿主机内核/Docker 引擎过旧（内核 < 5.6，或老版本 Docker 的默认 seccomp profile 对未知 syscall 返回 ENOSYS），而内网容器的 tar 版本比 docker-host
镜像里的新（新到不再回退）。41 秒时钟漂移也佐证那是一台缺少维护的老机器。

修复选项（按代价排序）

┌────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  方案  │                                                                         做法                                                                         │
├────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 最快   │ 内网后端 properties 加 harness.a2a.sandbox.hydrate-enabled=false（kill-switch 已接好在 V2SandboxConfig.java:118），跳过 tar 上传，容器文件靠部署同步 │
├────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 根治   │ 内网宿主机升级内核 ≥ 5.6 / Docker ≥ 24，或重建共享容器时加 --security-opt seccomp=unconfined                                                         │
├────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 镜像侧 │ 内网镜像里的 tar 降级到有 openat 回退的版本                                                                                                          │
└────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

请在内网 docker 宿主机上跑这三条确认

uname -r && docker version --format 'server={{.Server.Version}}'
docker exec <共享容器名> tar --version
docker exec <共享容器名> python3 -c "import ctypes,errno; libc=ctypes.CDLL(None,use_errno=True); libc.syscall(437,b'/tmp/x',0,0); print('openat2 ->',
errno.errorcode.get(ctypes.get_errno()))"

第三条如果打印 ENOSYS 就实锤了（437 = openat2 的 syscall 号）。

另外一个小疑点供你确认：异常里的路径是 \workspace（反斜杠）— Path.of("/workspace").toString() 只有在 Windows JVM 上才这样输出，Linux 会打 /workspace。如果内网后端是 Linux
跑的，那这份日志实际来自某台 Windows 上的 JVM；如果内网后端本来就部署在 Windows 上，则没有矛盾。

Sources:
- Red Hat Bugzilla 2437037 — tar started using openat2(), Cannot open: Function not implemented (https://bugzilla.redhat.com/show_bug.cgi?id=2437037)
- Red Hat Bugzilla 2447287 (https://bugzilla.redhat.com/show_bug.cgi?id=2447287)
- Colima #1613 — QEMU lacks openat2() (https://github.com/abiosoft/colima/issues/1613)
- docker-brew-fedora #125 — tar openat2 errors on old runtimes (https://github.com/fedora-cloud/docker-brew-fedora/issues/125)

✻ Cogitated for 27m 46s

❯ 内网后端 properties 加 harness.a2a.sandbox.hydrate-enabled=false  要加在那个配置文件中

Searched for 2 patterns (ctrl+o to expand)

● 加到内网后端实际激活的那个 profile 文件里。判断依据是内网后端启动日志开头 The following N profiles are active: 那一行：

┌─────────────────────────────────────────────────────────────────────────┬───────────────────────────────────────────────────────────────────────────────┐
│                            内网后端运行方式                             │                                 加到哪个文件                                  │
├─────────────────────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤
│ 容器内跑（intranet-deploy.sh 设的是 SPRING_PROFILES_ACTIVE=dev,docker） │ application-docker.properties（就是你现在打开的这个，它最后加载、优先级最高） │
├─────────────────────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤
│ Windows JVM 上跑（dev,sandbox-windows，和你本地一样的拓扑）             │ application-sandbox-windows.properties                                        │
└─────────────────────────────────────────────────────────────────────────┴───────────────────────────────────────────────────────────────────────────────┘

加这一行：

# Intranet workaround: 老 docker 宿主机 openat2 返回 ENOSYS, tar 解压失败, 跳过 hydrate 上传

两个补充点：

1. 不用重新打 jar 的快捷方式 — 配置在 jar 里改起来麻烦，可以直接在启动时覆盖（@ConfigurationProperties(prefix="harness.a2a") 支持松散绑定）：
   - 环境变量：HARNESS_A2A_SANDBOX_HYDRATE_ENABLED=false
   - 或启动参数：java -jar app.jar --harness.a2a.sandbox.hydrate-enabled=false

如果内网是容器跑的，在 intranet-deploy.sh 的 docker run 里加一行 -e HARNESS_A2A_SANDBOX_HYDRATE_ENABLED=false 即可。
2. 本地别加 — docker-host 的 tar 是正常的（我已验证），本地/dev 环境的 hydrate 该保留。

另外注意一个矛盾点：如果内网真是 dev,docker 跑的，harness.a2a.sandbox.enabled=false 本不该走到 DockerSandbox hydrate 这条路——所以内网后端大概率不是 dev,docker（结合日志里
\workspace 的反斜杠，更像是 Windows JVM + sandbox-windows 拓扑）。确认一下内网启动日志的 active profiles 再决定加到哪个文件。
