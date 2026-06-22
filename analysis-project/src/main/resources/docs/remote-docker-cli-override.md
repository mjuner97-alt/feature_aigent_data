# Remote Docker CLI override 变更清单

下面是本次改动的集中清单，可直接复制 TSV 内容到 Excel。

```tsv
序号	状态	文件	路径	修改类型	修改内容	内网同步建议	验证情况
1	修改	AgentscopeA2aApplication.java	analysis-project/src/main/java/com/agentscopea2a/AgentscopeA2aApplication.java	启动参数修复	启动入口从 SpringApplication.run(AgentscopeA2aApplication.class) 改为 SpringApplication.run(AgentscopeA2aApplication.class, args)，确保 --spring.profiles.active=dev,sandbox-windows 等命令行参数生效。	内网必须同步，否则通过命令行指定 sandbox-windows profile 不会生效。	已验证日志显示 The following 2 profiles are active: "dev", "sandbox-windows"。
2	修改	SandboxProperties.java	analysis-project/src/main/java/com/agentscopea2a/harness/config/SandboxProperties.java	新增配置项	在 harness.a2a.sandbox 配置组下新增 remote-docker-enabled、remote-docker-ssh-target、remote-docker-ssh-options[]、remote-docker-timeout-seconds。	内网同步时保留配置字段；具体 ssh target 可按内网 alias 调整。	已通过 mvn -f analysis-project/pom.xml -DskipTests package。
3	修改	FilesystemConfig.java	analysis-project/src/main/java/com/agentscopea2a/harness/config/FilesystemConfig.java	配置注入	在 sandbox filesystem 初始化时调用 DockerCliRunner.configure(...)；remote-docker-enabled=true 时记录远程 Docker 执行日志。	内网必须同步，否则新增 remote-docker 配置不会注入到底层覆盖类。	启动日志已出现 Remote Docker mode ON — executing docker CLI through ssh target=docker-host timeout=60s。
4	新增	DockerCliRunner.java	analysis-project/src/main/java/io/agentscope/harness/agent/sandbox/impl/docker/DockerCliRunner.java	新增覆盖包工具类	新增统一 Docker 命令执行器；本地模式执行 docker <args...>；远程模式执行 ssh -o BatchMode=yes <sshOptions...> <sshTarget> docker <args...>；支持阻塞执行和流式执行。	内网必须同步；这是 Windows 本地不装 docker.exe 的核心入口。	已通过编译；启动日志确认 remote Docker runner 配置生效。
5	新增	DockerSandbox.java	analysis-project/src/main/java/io/agentscope/harness/agent/sandbox/impl/docker/DockerSandbox.java	同包同名覆盖	新增同包同名类覆盖依赖 JAR 中的 DockerSandbox；将 docker run/start/stop/rm/inspect/exec 以及 tar hydrate/persist 调用统一改为 DockerCliRunner；保留 shared container 下 containerOwned=false 不 stop/rm 的行为。	内网必须同步；注意以后升级 agentscope-harness 版本时要重新 diff 上游 DockerSandbox。	已通过编译；应用可启动到 sandbox filesystem 初始化阶段。
6	修改	DockerSandboxClient.java	analysis-project/src/main/java/io/agentscope/harness/agent/sandbox/impl/docker/DockerSandboxClient.java	同包覆盖类增强	shared container attach/resume 阶段的 docker inspect 从本地 ProcessBuilder("docker", ...) 改为 DockerCliRunner.run(...)；远程模式下会执行 ssh docker-host docker inspect -f {{.Id}} <container>。	内网必须同步；否则 shared container 解析仍依赖本地 docker.exe。	已通过编译。
7	修改	application-dev.properties	analysis-project/src/main/resources/application-dev.properties	远程地址调整	MySQL JDBC 地址从 jdbc:mysql://116.148.121.118:3306/agentscope 调整为 jdbc:mysql://116.148.100.114:3306/agentscope；相关注释同步更新。	内网同步时替换为内网 MySQL 地址；JDBC 不能使用 ssh alias，必须是数据库可访问主机/IP。	已验证 116.148.100.114:3306 TCP 可连，应用成功初始化 MySQL 表。
8	修改	application-sandbox-windows.properties	analysis-project/src/main/resources/application-sandbox-windows.properties	Windows 沙箱配置调整	说明从 DOCKER_HOST=ssh://... + 本地 docker.exe 改为 ssh docker-host docker ...；新增 harness.a2a.sandbox.remote-docker-enabled=true、remote-docker-ssh-target=docker-host、remote-docker-timeout-seconds=60；artifacts/skills/memory ssh-target 统一使用 docker-host；为 remote Docker、artifacts、skills、memory SSH 增加 StrictHostKeyChecking=accept-new 和 LogLevel=ERROR，避免首次连接 known_hosts 警告污染 stderr。	内网同步时建议继续使用 docker-host alias，只替换 SSH config 中的 HostName；保留 LogLevel=ERROR，避免 [stderr] Warning: Permanently added ... 被业务当路径解析。	已验证应用加载 application-sandbox-windows.properties，并输出 Remote Docker mode ON。
9	修改	ssh.md	analysis-project/src/main/resources/docs/ssh.md	SSH 文档更新	docker-host SSH alias 的 HostName 改为 116.148.100.114；DOCKER_HOST 示例改为 ssh://docker-host；相关 SSH 验证命令更新到新 IP/alias。	内网同步时替换 HostName 为内网远端 Docker 主机，保留 Host docker-host 别名。	已验证 ssh -o BatchMode=yes docker-host 'echo ok && hostname' 可通。
10	新增	remote-docker-cli-override.md	analysis-project/src/main/resources/docs/remote-docker-cli-override.md	变更清单文档	集中记录本次 Windows 远程 Docker CLI override 的所有代码、配置、文档改动，提供 Excel/TSV 格式清单。	内网同步时可直接复制本 TSV 到 Excel，逐项核对修改。	当前文档即本文件。
```

## 核心结论

```tsv
项	结论
Windows 本地是否需要 Docker Desktop	不需要
Windows 本地是否需要 Docker daemon	不需要
Windows 本地是否需要 docker.exe	不需要
Windows 本地是否需要 ssh.exe	需要
Docker 实际运行位置	远端 Linux
远端 Docker 调用方式	ssh docker-host docker ...
共享容器名称	agentscope-shared-demo
Spring 启动 profile	dev,sandbox-windows
验证启动命令	java -jar analysis-project/target/analysis-project-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,sandbox-windows
```

## 已执行验证

```tsv
序号	验证项	命令/现象	结果
1	SSH alias 可用	ssh -o BatchMode=yes docker-host 'echo ok && hostname'	通过
2	远端 Docker 可用	ssh root@116.148.100.114 "echo ok && docker ps"	通过，能看到 agentscope-shared-demo
3	MySQL TCP 可达	连接 116.148.100.114:3306	通过
4	项目打包	mvn -f analysis-project/pom.xml -DskipTests package	通过
5	profile 生效	启动日志 The following 2 profiles are active: "dev", "sandbox-windows"	通过
6	远程 Docker 配置生效	启动日志 Remote Docker mode ON — executing docker CLI through ssh target=docker-host timeout=60s	通过
7	应用启动	Tomcat started on port 8081；Started AgentscopeA2aApplication	通过
```
