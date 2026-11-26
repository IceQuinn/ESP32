# AI 对话 (ai-app)

一个独立的安卓 AI 对话应用，当前接入 **DeepSeek** 模型，支持流式逐字回复。

与上级目录的 ESP32 灯控工程、`android-app/` 仍是两个独立应用，各自构建、各自安装。
本应用在聊天功能之上集成了 ESP32 的 BLE 控灯能力。

## 功能

- 接入 DeepSeek API（`deepseek-chat` / `deepseek-reasoner`）
- 流式输出：回复逐字显示，可随时点“停止”中断
- **多会话与历史对话**：点左上角「+」开始新对话，之前的会话自动归档；
  点顶部标题栏可打开历史面板，重新打开或删除历史会话
- **本地持久化**：会话与历史记录保存在本机，关闭应用后重新打开仍在，
  并自动打开上次的会话
- **时间显示**：每条消息气泡下方显示发送时间；跨天自动插入日期分隔；
  历史列表显示会话的最后活跃时间
- **白色系界面**：参考 DeepSeek / ChatGPT，纯白背景、浅灰气泡、深色文字
- 设置页：填写 API Key、切换模型、**测试连接**
- 错误提示中文化：401 无效密钥、402 余额不足、429 频率限制等
- 深色模式自动适配
- **ESP32 控灯**：底部「灯控」Tab 可手动扫描/连接/开关灯；
  聊天里说「打开 esp32 的灯」「关闭 esp32 的灯」会直接调用 BLE 指令，
  不消耗 API 额度，回执标注为「本地指令 · 未经过模型」

## 安装

APK 位于 `dist/AI-Chat-debug.apk`，也可自行构建后从
`app/build/outputs/apk/debug/app-debug.apk` 获取。

```powershell
cd E:\Project\esp\ESP32\ESP\android-app
. .\android-env.ps1
cd ..\ai-app
gradle assembleDebug
```

或用 USB 调试安装：

```powershell
adb install -r dist\AI-Chat-debug.apk
```

小米 / Redmi 手机若提示 `INSTALL_FAILED_USER_RESTRICTED`，需在
**设置 → 更多设置 → 开发者选项** 中开启 **USB 安装** 与 **USB 调试（安全设置）**。

## 首次使用

1. 打开 App，会自动进入设置页
2. 到 [platform.deepseek.com](https://platform.deepseek.com) 创建 API Key 并粘贴
3. 选择模型，点 **测试连接** 确认可用
4. 点 **保存** 回到聊天页开始对话

## API Key 安全说明

- Key 只保存在本机应用私有目录（`SharedPreferences`），**不会上传到任何服务器**，也不在源码中
- APK 内不含任何密钥，请勿把 Key 提交到 git
- 当前使用普通 SharedPreferences，未做加密存储；已 root 的设备理论上可读取
- 若在共用设备上使用，建议结束后点“清除已保存的 Key”

## ESP32 控灯

### 指令识别顺序

1. **本地关键词**：命中「打开/关闭/点亮/熄灭 + 灯」等明确说法时直接执行 BLE 写入，
   不请求 DeepSeek，也不消耗 token；
2. **AI 兜底**：消息里提到灯/ESP32 但说法不明确（如「把那个灯弄亮」）时，
   先用一次 Function Calling 判断意图，判不出来再按普通聊天处理；
3. 疑问句、否定句（「灯怎么打开？」「不要开灯」）一律不执行，交给 AI 回答。

### BLE 协议

与固件 `main/bluetooth.c` 保持一致：

- 设备名 `ESP32_BLE`
- 服务 `12345678-1234-5678-1234-56789abcdef0`
- 特征 `12345678-1234-5678-1234-56789abcdef1`，支持读与带响应写
- 写 `0x01` 开灯、`0x00` 关灯；读一字节返回当前状态

聊天与灯控页共用同一个 GATT 连接（`BleRepository` 单例），
所以在灯控页连上之后，聊天里的指令无需重新扫描。

### 权限

Android 12+ 只需要「附近设备」权限（`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`），
扫描声明了 `neverForLocation`，不额外申请定位权限；`minSdk` 因此提升到 31。

## 技术说明

| 项 | 说明 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose (Material 3) |
| minSdk / targetSdk | 31 / 36 |
| 网络 | `HttpURLConnection`，手写 SSE 解析，无第三方 HTTP 库 |
| 依赖 | 仅 AndroidX（core-ktx、activity-compose、lifecycle）+ Compose |
| 权限 | 仅 `android.permission.INTERNET` |

接口调用的是 DeepSeek 的 OpenAI 兼容接口：
`POST https://api.deepseek.com/chat/completions`，`Authorization: Bearer <key>`，
`stream: true` 时按 `data: {...}` 逐行解析，以 `data: [DONE]` 结束。

### 代码结构

```
app/src/main/java/com/example/aichat/
├── MainActivity.kt              单 Activity，聊天页/设置页切换
├── data/
│   ├── ChatMessage.kt           消息模型（含时间戳）与可选模型列表
│   ├── Conversation.kt          会话模型（标题、消息、时间）
│   ├── ConversationStore.kt     会话 JSON 文件持久化
│   ├── DeepSeekClient.kt        HTTP + SSE 流式客户端
│   └── SettingsStore.kt         API Key 与模型的本地存储
├── util/
│   └── TimeFormat.kt           时间格式化（今天/昨天/日期/分隔标签）
└── ui/
    ├── ChatViewModel.kt         会话管理、持久化、聊天状态与流式逻辑
    ├── ChatScreen.kt            聊天界面（气泡、时间、历史面板、输入栏）
    ├── SettingsScreen.kt        设置界面
    └── Theme.kt                 白色系主题配色（含深色模式）
```

## 已知限制 / 后续计划

- 仅支持 DeepSeek，暂不支持其他厂商
- 不支持图片输入、函数调用、语音
- 回复为纯文本显示，未渲染 Markdown
- 未做 token 统计与费用显示
- 未实现“新建对话后清空当前页但保留历史”以外的批量管理

## 修复记录

### `nullnull...` 污染正文（Reasoner 模型）

**现象**：使用 `deepseek-reasoner` 时，AI 回复内容出现大量连续的 `null`。

**原因**：思考阶段的 SSE chunk 中 `delta.content` 字段为 JSON `null`。
`org.json` 的 `optString(key, fallback)` 在“字段存在但值为 null”时返回的是
**字面量字符串 `"null"`**，而不是 fallback，于是每个 chunk 都被当作正文追加。

**修复**：新增 `safeString()`，显式处理 `JSONObject.NULL`，并在
`parseDelta`、`ping`、错误信息解析处统一使用。同时 `appendToAssistant`
忽略空 chunk，避免无效追加。
