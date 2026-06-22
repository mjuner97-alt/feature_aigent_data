# 1. 本机有密钥就跳过,没有就生成
[ -f ~/.ssh/id_ed25519 ] || ssh-keygen -t ed25519 -N '' -f ~/.ssh/id_ed25519

# 2. 把公钥推到远端(这一步会提示输一次密码,以后就免密了)
ssh-copy-id root@116.148.100.114
# 如果远端禁了密码登录,改成手动追加:
# cat ~/.ssh/id_ed25519.pub | ssh root@<host-with-password-tunnel> 'cat >> ~/.ssh/authorized_keys'

# 3. 验证免密通了
ssh -o BatchMode=yes root@116.148.100.114 'echo ok && hostname'






ssh root@116.148.100.114 'mkdir -p /opt/agentscope-workspace/harness-a2a/artifacts'



Host docker-host
  HostName 116.148.100.114
  User root
  ControlMaster auto
  ControlPath /tmp/.ssh-mux-%C
  ControlPersist 10m



export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# 让 harness 在远端起容器
export DOCKER_HOST=ssh://docker-host

# 让 CSV artifact 走 SSH 写远端
export HARNESS_A2A_ARTIFACTS_REMOTE_ENABLED=true
export HARNESS_A2A_ARTIFACTS_SSH_TARGET=root@116.148.100.114
export HARNESS_A2A_ARTIFACTS_REMOTE_ROOT=/opt/agentscope-workspace/harness-a2a/artifacts

java -jar target/agent_init-1.0-SNAPSHOT.jar --spring.profiles.active=sandbox
