# Python 3.10 (需先装 gcc/make，麒麟一般自带)
tar -xzf Python-3.10.15.tgz
cd Python-3.10.15
./configure --prefix=/opt/python3.10 --enable-optimizations
make -j$(nproc) && make install

# PyCharm
tar -xzf pycharm-community-2024.3.1.1.tar.gz -C /opt/
/opt/pycharm-community-2024.3.1.1/bin/pycharm.sh

# ============ 设置 python3.10 为默认 (推荐: PATH 优先, 不动系统文件) ============
# 1) PATH 方式: 让 /opt/python3.10/bin 排在系统路径前面 (对当前用户生效)
echo 'export PATH=/opt/python3.10/bin:$PATH' >> ~/.bashrc
source ~/.bashrc

# 2) 补一个 python 命令 (编译安装只生成 python3, 没有裸 python)
sudo ln -sf /opt/python3.10/bin/python3 /usr/local/bin/python

# 3) 验证 (应显示 Python 3.10.15)
python --version
python3 --version
which python python3        # 应指向 /opt/python3.10/bin 或 /usr/local/bin
pip3 --version

# ============ 备选: update-alternatives 方式 (全系统生效) ============
# ⚠️ 麒麟是 RHEL 系, 系统 yum/dnf 依赖自带 python3, 覆盖 /usr/bin/python3
#    可能导致 yum 报错。确认系统工具链无影响再用此法。
sudo update-alternatives --install /usr/bin/python3 python3 /opt/python3.10/bin/python3.10 100
sudo update-alternatives --install /usr/bin/python python /opt/python3.10/bin/python3.10 100
sudo update-alternatives --config python3   # 交互式选择, 输入对应序号
# 恢复系统自带 python3:
# sudo update-alternatives --config python3 选回原路径即可

