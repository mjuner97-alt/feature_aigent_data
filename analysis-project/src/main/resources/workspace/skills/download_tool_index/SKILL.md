---
name: data_detail_download_tool_index
description: 问题数据明细查看、下载和导出链接工具索引。根据用户意图选择子工具 ID，配合 toolMetaInfo 和 router_tool 调用，返回明细下载导出链接。
---

# 工具索引

用于在子 agent 只拥有 `toolMetaInfo` 和 `router_tool` 两个元工具时，先确定业务子工具 `toolId`。

## 固定调用流程

1. 根据用户意图在本技能中选择一个 `toolId`。
2. 调用 `toolMetaInfo(toolId="...")` 获取该工具的描述、入参、参数类型、必填项和 routerExample。
3. 按元信息组装 JSON 字符串，调用 `router_tool(paramsJson="...")`。
4. 只使用 `router_tool` 的真实返回结果回答用户，不得编造工具输出。

## 可用工具索引

### 问题明细（当问题状态涉及「已关闭」「关闭」「已闭环」等关键信息，或不涉及问题状态时，使用该类工具）

| 工具ID | 功能描述 |
|--------|----------|
| generateDownloadUrl | 生成文件下载链接。返回包含下载URL的文本说明，用户可通过该链接下载文件。 |
