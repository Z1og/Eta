# Eta2

**Eta 的定制分支**：在 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 的 Agent 运行时之上，重点做**稳定性、上下文体积与逆向技能体系**。

<p><a href="https://github.com/Z1og/Eta2/releases"><img src="https://img.shields.io/github/downloads/Z1og/Eta2/total?logo=github&amp;label=%E4%B8%8B%E8%BD%BD%E9%87%8F&amp;color=1677FF" alt="GitHub Releases 累计下载量"></a> <img src="https://img.shields.io/badge/minSdk-34-3DDC84?logo=android&amp;logoColor=white" alt="minSdk 34"> <img src="https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin 2.4.10"> <img src="https://img.shields.io/badge/AGP-9.3.2-3DDC84?logo=android&amp;logoColor=white" alt="AGP 9.3.2"> <img src="https://img.shields.io/badge/Skills-200%2B%20%E5%86%85%E7%BD%AE-7F52FF" alt="200+ built-in skills"></p>

产品本体仍是 Eta，用法与上游一致：自备模型 API Key（BYOK），Android 14+，基础功能不需要 Root。

---

## 这个分支改了什么

### 稳定性：把"卡死/丢消息"这一类问题逐个钉死

| 问题 | 原因 | 处理 |
|---|---|---|
| 发消息直接卡死 | 会话 `journal` 无界增长 → 整段编码进 `journal_json` 时 OOM → 写前落盘判失败 → 失败分支回主线程跑 `linkRun` 的 M×N 字符串拼接 | journal 按 4MB 字符上限保留尾部；编码 OOM 时逐级退化（4MB→512KB）再存；`linkRun` 改哈希索引单趟 O(n) |
| 回到前台必崩 | 重建 ViewModel 时把 39 个会话的 messages + history + journal 全量读进堆（实测堆占用 265MB / 上限 256MB） | 只为选中会话还原 history/journal，其余切到该会话或发送前再补；`restoreText` 按声明长度预分配；开启 `largeHeap` |
| 运行中无响应、不报错 | 运行线程只有 `finally` 没有 `catch`，异常后无人回复，客户端 `await()` 无超时 | 兜住 `Throwable` 并回一条可读的失败结果；拒绝请求时也回执 |
| 越聊越慢 | 压缩找切分点是 O(n²)（每档重扫前缀 + 逐条解析 tool_calls） | 单趟记录前缀未闭合集合，O(n) |

同时加了**日志落盘 + 主线程卡死看门狗（含阶段追踪）+ 设置里一键导出**——上面几条都是靠它定位的，不是猜的。

### 性能：不再把历史搬来搬去

- **发送不再跨进程传历史**：只传 `historyRef`（会话 id），runtime 直接从库里读那份已落盘的上下文。省掉每次约 700ms 的编码 + 临时文件传输。
- **冷启动只解析选中会话的上下文**：实测 `conversation load slow` 从 9.2s 降下来，内存压力同步下降。

### 注入：能关的都做成开关，默认更克制

- **技能正文自动注入只保留原版三个**（`self-improving-agent` / `skill-creator` / `skill-installer`）。其余技能正文动辄几千字、靠关键词猜，收益低；改成需要时由模型自己 `skills_read`。
- **候选短名单、已启用技能索引表、运行中技能提示全部移除**：同属关键词猜测，且索引表每次请求都要带上几百行元信息。
- **破甲记忆：随包装配，但默认不注入**。装配只让「编辑作战协议」有内容可看；是否注入只看一个开关。
- **设置 → 上下文与扩展**新增三行开关：**逆向能力**（一键启停除原版外全部技能）、**作战协议**、**破甲记忆注入**。
- **新会话默认带角色**：沿用上次使用的角色卡，不必每次从角色列表进入；开关可关。
- 角色的注入链路（persona / 世界书 / depth prompt / 后置指令 / 剧情记忆）**保持原样**。

### 技能体系与同步

- 随包 **200+ 个技能**，除原版三个外均为逆向与安全方向：APK/DEX/SO、Frida、脱壳、抓包与反检测、IL2CPP/UE dump、硬件追踪、渗透、游戏安全等。
- **逆向模式总开关**：一键启停全部逆向技能，并常驻注入逆向交付契约（首行命名交付物、缺参自造占位符、直接给可执行命令）。
- 仓库自带三源技能适配脚本（`hanshuang-to-eta.py` 等）与 CI 管线。
  **自动同步已关闭**：`sync-build.yml` 只保留手动触发（不再定时、不再接受 webhook）；
  日常推送到 main 只跑 `build-only.yml` 出 APK，不合并上游、不动技能。需要适配时再说。

---

## 原版要点

> 以下为上游 Eta 的简介，完整说明见 [上游仓库](https://github.com/Mangi-11/Eta) 与 `docs/`。

### 系统级能力

- **系统操作**：直接调用 Android API 与系统 Intent（闹钟、媒体、音量、设备状态）。
- **厂商数据**：对应系统与授权下检索小布记忆、便签、录音摘要等。
- **系统入口**：通过 Xposed 接管电源键、小布与超级小爱，从熟悉的入口发起任务。

### 执行工具

- **GUI Agent**：无障碍 UI 树 + 控件定位 + 按需截图，浮层显示状态，可停止/接管。
- **内置浏览器**：WebView 加载 JS 页面、读正文、操作 DOM、截图。
- **终端与文件**：Android user/root Shell、Alpine / Debian Linux、会话、异步命令与守护任务。

### 上下文与扩展

- **个人上下文**：通知、应用使用、位置；相册/日历/短信/录音等专用检索需 Root。
- **长期记忆**：本机 `MEMORY.md`，核心内容按预算进上下文，其余按需读取。
- **Skills / MCP**：技能按需加载、支持 GitHub 与 ZIP 安装；MCP 走 Streamable HTTP，工具逐项启用。

### Agent Runtime 与角色

- 运行时在 App 内，聊天与系统助手共用同一个 Agent Loop；工具按 JSON Schema 校验并检查权限。
- 支持流式事件、steering、取消、增量 transcript 与中断恢复（不自动重放操作）。
- **角色**：可导入兼容酒馆的 PNG / JSON 角色卡，支持用户人设、内嵌世界书与独立剧情记忆；角色会话仍能调用手机工具。

### 模型与 BYOK

内置 OpenAI、Anthropic、阿里百炼、DeepSeek、Kimi、MiMo、MiniMax、StepFun、硅基流动、OpenRouter 等配置，也可自定义服务地址/请求头/请求体；支持 Chat Completions、Responses 与 Anthropic Messages（SSE、Tool Calling、图片输入、推理内容）。可从 [models.dev](https://models.dev/) 目录挑选兼容服务。

### 权限与数据边界

系统工具、敏感读取、敏感操作、终端与文件、网页浏览、记忆各有独立开关。任务所需的对话、图片与工具结果会发送给你配置的模型服务；自定义 HTTP 地址会明文传输 API Key。会话、记忆与模型配置支持导入导出（备份含 API Key）。

---

## 快速开始

1. 从 [Releases](https://github.com/Z1og/Eta2/releases) 下载 APK，在"模型提供商"填 API Key 并选模型（需要 Tool Calling；看图需模型支持图片输入）。
2. 按需授权：GUI 需要无障碍；通知、应用使用情况分别授权；位置需"始终允许"。
3. 需要逆向/安全能力时到「技能」页打开**逆向模式**，或逐项启停；也可用 GitHub 仓库 / 本地 ZIP 安装。
4. 开始对话。需要命令行时在"Linux 工具环境"安装发行版与开发工具。

- **普通设备**：Android 14+，可聊天、浏览器、记忆、Skills、MCP、普通终端与私有工作区。
- **Root 设备**：开放系统设置修改、应用管理、受保护文件与专用数据检索、Root Shell 与 chroot。
- **LSPosed**：开放厂商助手接管与系统快捷入口，部分功能另需 Root。

---

## 文档

- [设备支持与权限边界](docs/ROOTLESS_SUPPORT.md)
- [技术实现](docs/TECHNICAL.md)
- [Agent Runtime](docs/AGENT_RUNTIME.md)
- [角色功能](docs/CHARACTERS.md)
- [语音配置](docs/VOICE.md)
- [终端原生组件](docs/TERMINAL_NATIVE.md)

## 参考与致谢

- [Mangi-11/Eta](https://github.com/Mangi-11/Eta)：本分支的上游。
- [Pi Coding Agent](https://github.com/earendil-works/pi)：Agent Runtime 的核心参考。
- [OmniBot](https://github.com/omnimind-ai/OmniBot)、[libxposed API](https://github.com/libxposed/api)、[Miuix](https://github.com/compose-miuix-ui/miuix)。
- 内置技能融合自多个社区开源技能仓库，感谢各位作者。

## 许可证

沿用上游：[PolyForm Noncommercial License 1.0.0](LICENSE)。未经[原作者](https://github.com/Mangi-11)书面授权，禁止个人二次分发、贩卖、收费代装及其他商业使用。

<sub></sub>社区：<a href="https://linux.do">LINUX DO</sub>
