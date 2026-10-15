# 动态参数模拟脚本

脚本 ID：`dynamic_params_version_year_mock`。注册信息位于高斯 `script_registry`，当前记录 ID 为 `6`，名称为“动态参数测试（版本数组、年份单值）”。

| 参数 | 类型 | 默认动态配置 |
| --- | --- | --- |
| `versions` | `string[]` | 最近三个完整月份，`{year}年{month}月份版本`，数组输出 |
| `year` | `string` | 当前年，`{year}年`，单值输出 |

在长任务或报告流程节点中选择此脚本后，默认值会显示为可修改的动态时间配置。

例如数据日期为 `2026-10-15` 时，执行入口收到的参数应为：

```json
{
  "versions": ["2026年7月份版本", "2026年8月份版本", "2026年9月份版本"],
  "year": "2026年"
}
```

`script_exec` 精确匹配此 ID 后直接返回 JSON：包含 `mock: true`、实际收到的 `params`、每个版本一行的 `rows`（订单数和销售额）及 `summary`。数据是固定生成的模拟数据，不查询业务数据库，也不访问 Python 文件。对话、试跑和后台流程共用同一模拟入口。直接调用工具时应传已解析的值；动态配置由上游流程或试跑服务计算。

参数定义：[schema](../scripts/dynamic_params_version_year_mock.schema.json)。注册 SQL：[Gauss SQL](../scripts/register_dynamic_params_version_year_mock.sql)，可重复执行且不会覆盖同 ID 的现有记录。新 Java 模拟分支需服务重新加载代码后生效。
