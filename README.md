# numAi5

面向 Android 5.0+（minSdk 21）的多供应商 AI 聊天助手（Java + XML），基于 [gohoski/numAi](https://github.com/gohoski/numAi) 的架构蓝本重制。

## 功能特性

- **多供应商 / 多模型**：配置一套 OpenAI 兼容中转站（NewAPI / One-API 等）地址与 API Key，即可使用 DeepSeek / Claude / GPT / Qwen 等模型；一段对话中可为不同消息选用不同模型（发送瞬间锁定，回合结束后可再切换）
- **思考等级四档**：Low / Default / High / MAX 输入栏实时切换；按 NewAPI 方言映射 `reasoning_effort`，中转不支持 `max` 时自动降级为 `high` 并提示
- **视觉识图**：相册 / 拍照附加图片（全尺寸拍摄 + 两步采样压缩防 OOM），按 OpenAI 标准 `content` 数组 + `image_url` base64 发送
- **流式输出**：SSE 流式正文与思考过程展示；生成中可取消，已收到部分保留
- **上下文管理**：请求自动保留最近 12 个完整回合（不拆散提问与回复），截断时轻提示
- **本地持久化**：聊天历史 AtomicFile 原子写入，进程崩溃不损坏文件

## 使用方法

1. 安装 APK，打开应用 → 右上角菜单 →「设置」
2. 填入中转站地址（形如 `https://api.example.com/v1`）与 API Key
3. 点「拉取模型列表」→ 选择默认模型 → 保存
4. 返回主界面即可聊天；输入栏内可切换模型、思考等级 🧠、附加图片（🖼×N · 清除）

## 已知问题

### 工具按钮自由摆放（已知，暂无能力解决）

输入栏工具按钮「长按拖动 → 屏幕任意位置摆放」功能在部分真机上实测异常（多次崩溃 / 拖影残留），2026-10-18 起标记为已知问题：

- **现状**：已先后尝试「系统 DragAndDrop」与「纯触摸状态机」两套实现，真机验证均未通过；
- **影响**：长按输入栏的工具按钮（图片 / 思考等级 / 模型）可能触发异常退出，其余功能不受影响；
- **规避**：不要长按工具按钮。若异常退出，重新打开应用即可继续；若按钮位置错乱，可在系统「设置 → 应用 → numAi5 → 清空数据」复位（会同时清空聊天记录与配置，谨慎使用）；
- **诚实声明**：该问题目前没有找到可靠的跨机型实现方案，修复状态暂停；欢迎有思路的开发者提 PR 或 issue 讨论。

## 注意事项

- **Android 5.0 - 7.0 提示**：连接使用 Let's Encrypt 证书的站点可能失败（ISRG Root X1 自 Android 7.1.1 起才进入系统信任库）。如遇连接问题，请改用证书链兼容性更好的中转地址
- 中转站需兼容 OpenAI Chat Completions 格式
- 应用只访问你配置的中转地址，不收集任何数据，API Key 仅保存在本机

## 构建

推荐使用 GitHub Actions（`.github/workflows/build.yml`）：推送 `main` 或提交 PR 时自动构建 release APK，在 Actions 页面的 Artifacts 中下载。

本地构建需 JDK 17 + Android SDK（compileSdk 34）：

```bash
./gradlew assembleRelease
```

发布签名：在仓库配置 Secrets（`RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`）后，CI 自动解码 keystore 并签名；未配置时产出未签名包。

## 致谢

- [gohoski/numAi](https://github.com/gohoski/numAi) — 架构蓝本
- [QuantumNous/new-api](https://github.com/QuantumNous/new-api) — 中转服务参考

## License

[MIT](LICENSE)
