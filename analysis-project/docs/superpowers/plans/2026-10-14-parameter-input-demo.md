# 参数输入演示实施计划

用户已确认：新增日期、地区数组、数量参数的 Python 模拟脚本，保留界面样式，仅缩小 JSON 备用框。

- [x] 添加标准输入测试：自定义参数影响输出、默认值、非法数量、HTML 转义。
- [x] 实现 scripts/parameter_input_demo.py 和 params schema。
- [x] 使用现有 script-registry/source API 注册独立演示脚本，不改已有脚本或流程。注册 id=5，scriptId=parameter_input_demo。
- [x] FlowNodeCard JSON 行数由 8 改 3，最小高度由 150px 改 76px。
- [x] 验证 Python 测试（4 项）、参数表单回归（22 项）、前端构建、注册元数据和保存源码。

后端执行器要求非空 datasources，因此声明 gauss；脚本不连接数据库。真实 API 试跑已越过数据源校验，但运行环境的 python3 命令退出 9009，当前服务需要配置可用 Python 或启用既有容器执行环境。脚本使用随附 Python 本地标准输入执行已验证成功。
