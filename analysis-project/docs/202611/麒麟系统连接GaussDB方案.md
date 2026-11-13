# 麒麟系统 Python 连接 openGauss(GaussDB) 方案

> 适用环境: 麒麟 V10 / Linux x64 (amd64) + Python 3.10, 内网离线
> 配套离线包: `docs/202611/py310whl/` (jpype1 轮子 + opengauss-jdbc jar + demo 脚本)

## 1. 为什么不用 psycopg2

openGauss 高版本默认密码认证是 **SHA256 SASL**, 社区 psycopg2 的 libpq 不认这个机制,
连接直接报:

```
none of the server's SASL authentication mechanisms are supported
```

即使服务端改成 md5 兼容也只是权宜之计。本项目已在内网验证过的方案是:

**JPype (Python 起内嵌 JVM) + opengauss-jdbc JDBC 驱动** — 与 Java 端同一个驱动
`org.opengauss:opengauss-jdbc:5.1.0`, 完整支持 SHA256 认证。

## 2. 离线安装包清单 (py310whl 目录)

| 文件 | 用途 |
|---|---|
| `jpype1-1.6.0-cp310-cp310-manylinux2014_x86_64.manylinux_2_17_x86_64.whl` | Python 桥接 JVM |
| `opengauss-jdbc-5.1.0.jar` | openGauss JDBC 驱动 (929KB) |
| `_gauss_jdbc.py` | 连接/查询封装 (直接拷到业务脚本同目录) |
| `demo_gauss_query.py` | 演示脚本, 验证连通性 |
| JDK17 x64 tar.gz (在 `bs4_offline/`) | JPype 需要 JVM, 用它 |

> cp310 轮子已按 Linux x64 标签下载, 不要在麒麟上用 pip 从公网重装。

## 3. 安装步骤 (麒麟机器上)

```bash
# 3.1 解压 JDK17 (JPype 起 JVM 依赖 JAVA_HOME)
tar -xzf OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz -C /opt/
echo 'export JAVA_HOME=/opt/jdk-17.0.21+9' >> ~/.bashrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
java -version          # 应输出 17.0.21

# 3.2 离线安装 jpype1 (复制 py310whl 目录到内网后)
pip3 install --no-index --find-links=. jpype1

# 3.3 驱动 jar 放固定位置
mkdir -p /opt/gauss
cp opengauss-jdbc-5.1.0.jar /opt/gauss/
```

## 4. 连接配置 (环境变量)

`_gauss_jdbc.py` 从 4 个环境变量读连接信息, 写入 `~/.bashrc`:

```bash
export GAUSS_JDBC_URL="jdbc:postgresql://<host>:<port>/<db>?currentSchema=<schema>"
export GAUSS_USER=<用户名>
export GAUSS_PASS=<密码>
export GAUSS_JAR=/opt/gauss/opengauss-jdbc-5.1.0.jar
```

说明:
- URL 前缀就是 `jdbc:postgresql://` (opengauss-jdbc 兼容 pg URL), **不是** `jdbc:opengauss://`。
- `currentSchema` 指定默认 schema, 不写则默认 public。
- 密码含特殊字符时 URL 里不要拼密码, 认证走 `GAUSS_USER/GAUSS_PASS` 变量。

## 5. 使用 demo

```bash
python3 demo_gauss_query.py
```

输出 `连通 OK, 服务端版本: ...` 即方案生效。

业务代码里直接用封装:

```python
from _gauss_jdbc import query_gauss

# :name 占位符自动转 JDBC ?, params 顺序替换
rows = query_gauss(
    "SELECT dept, count(*) AS cnt FROM t_project WHERE version = :v GROUP BY dept",
    params={"v": "2026年10月份版本"},
)
# 返回 list of dict, key 是 SQL AS 别名
for r in rows:
    print(r["dept"], r["cnt"])

# IN 查询: 传 list 自动展开成 (?,?,?)
rows = query_gauss(
    "SELECT * FROM t_project WHERE project_no IN :nos",
    params={"nos": ["KJ2026001", "CC2026002"]},
)
```

## 6. 已踩过的坑 (务必看)

1. **`import jpype.imports` 必须有** — 没有这行 `from java.sql import ...` 会报 ModuleNotFoundError。
2. **JVM 进程内只能启动一次** — `_gauss_jdbc.query_gauss` 内部已处理, 业务代码不要自己调 `jpype.startJVM`。
3. **openGauss 把空字符串当 NULL** — `''` 插库读回来是 None, 判断逻辑不要写 `if value == ''`。
4. **方言差异** — 不支持 `ADD COLUMN IF NOT EXISTS` 等 PG12+ 语法, DDL 先在测试库过一遍。
5. **列名中文** — 驱动返回的列名是 JString, 封装里已显式转 Python str; 若自己裸调 JDBC 记得同样处理。
6. **JString/JInt 等类型** — `rs.getObject()` 拿到的是 Java 类型, 封装里已逐列转换成 Python 类型; 数值是 JLong/JDouble。
7. **`_gauss_jdbc.py` 的 `GAUSS_JAR` 默认值**指向 Docker 镜像路径 `/root/.m2/...`, 裸机部署必须显式设置 `GAUSS_JAR`。

## 7. 验证清单

- [ ] `java -version` 输出 17.0.21
- [ ] `pip3 show jpype1` 显示 1.6.0
- [ ] `python3 demo_gauss_query.py` 打出版本行
- [ ] 业务 SQL 带中文参数查询结果正确 (UTF-8)
- [ ] IN 数组参数查询正常
