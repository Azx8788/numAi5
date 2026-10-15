# numAi5 开发约束清单（源自专家审查，逐条落实）

## A 类硬伤（必须修）
- **A1 reasoning_content 按模型开关回传**：`ModelDef.keepReasoningInHistory`。DeepSeek V3.2+/V4 thinking=true→true；其余→false（R1/Claude/GPT 带历史会 400）。`buildMessages` 中仅白名单模型回传。命中 400 含 reasoning_content 时翻转开关重试一次。
- **A2 字段名**：tool 消息用 `tool_call_id`（非 `_id`）；流式工具调用聚合 `delta.tool_calls`（非 `s`）。
- **A3 四档映射标准/方言双模式**：`max` 非官方值。标准模式 MAX→`high`；方言(NewAPI)→`max`。**DEFAULT 显式发 `medium`**（GPT-5.x 默认 none，不发=不思考）。
- **A4 max_tokens 按家族门控**：o系/gpt-5→`max_completion_tokens`，其余→`max_tokens`；推理模型不发 `temperature`。
- **A5 权限**：ACTION_PICK 的 content:// URI 已带授权，无需存储权限；CAMERA 在 Android 6+ 设备运行时申请；导出走 `MediaStore.Downloads` 免存储权限（WRITE_EXTERNAL_STORAGE 在 10+ 无效）。运行时权限取决于**设备系统版本**而非 targetSdk。
- **A6 历史图片占位化**：仅"当前回合" user 消息带 base64；更早历史替换 `[图片]` 占位；原图存 `filesDir/images` 仅 UI，持久化只存文件名。

## B 类雷点
- **B1 中转兼容双模式**：默认统一 `reasoning_effort`；设置页"本地转译"开关，启用后客户端本地写 Claude→`thinking.budget_tokens`、Qwen→`enable_thinking`+`thinking_budget`。`/models` 返回名剥前缀去重。
- **B2 TLS**：ISRG Root X1 仅 Android 7.1.1+ 信任库；Let's Encrypt 在 5/6/7 可能验证失败（文档明示）。`usesCleartextTraffic` targetSdk 28+ 默认 false，http 中转会连不上——network_security_config 按域名放行或强制 https。TLS 1.2 API 16+ 支持、20+ 默认启用，无需 trust-all hack。
- **B3 上下文截断**：保留 system + 最近 N 轮 + 字符预算（~4 chars/token）裁剪，UI 轻提示。
- **B4 超时放宽**：readTimeout 300s（推理首字节可超 60s）。断流→`isError`+显示"生成中断"+保留已收到部分。
- **B5 工具门控**：`ModelDef.supportsTools`。R1 不支持 function calling；V3.2 thinking 支持但要求 reasoning 回传。工具按钮与 tools 字段随模型联动。
- **B6 双通道去重**：`reasoning_content` 非空时跳过 content 中 `ITHINK` 标签解析，防思考内容显示两遍。

## C 类小项
- `apply()` 非 `commit()`（API 21 可用，主线程 commit 会 ANR）。
- Gson+R8 必加 keep 规则（见 proguard-rules.pro）。
- `android.util.Base64` 必须 `NO_WRAP`。
- system 消息永远纯字符串 content。
- 错误透出：解析 `{"error":{"message"}}` 显示到气泡。
- `finish_reason:"length"`→提示"因长度截断"。
- `allowBackup=false`；日志永不打 apiKey。
- M1 先做配置页（baseUrl/key）才能验 `/models`。

## 修订 ModelDef 字段
`id, displayName, providerId, supportsThinking, supportsVision, supportsTools=true, keepReasoningInHistory=false, needsMaxCompletionTokens=false, isReasoningOnly=false, effortDialect="standard"`

## 四档映射
| 档位 | 标准 | 方言(NewAPI) |
|---|---|---|
| Low | low | low |
| Default | medium | medium |
| High | high | high |
| MAX | high | max |
