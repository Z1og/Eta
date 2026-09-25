---
name: hanshuang-router
description: |
  寒霜安全技能路由总控（Eta 适配版）。根据用户意图自动路由到对应的安全研究 Skill。
  触发关键词：逆向、破解、渗透、外挂、APK分析、Frida、IDA、协议逆向、内存分析、二进制分析、
  CTF、漏洞利用、免杀、游戏安全、移动端安全。
compatibility: Requires Eta Agent Runtime with root shell and Alpine Linux environment.
metadata:
  user-invocable: "false"
  source: hanshuang-codex-adapted
---

# 寒霜安全技能路由（Eta 适配版）

用户提出安全研究类请求时，按本路由表自动分流到对应 Skill。

## 路由表

| 优先级 | 关键词 | 工作流 | Skill |
|---|---|---|---|
| 200 | ctf/夺旗/靶场/challenge/flag | ctf | reverse-engineering |
| 190 | 破解/crackme/keygen/注册机/序列号/许可证/卡密/license/激活/去验证/授权码 | crack | crack-keygen |
| 185 | 逆向/反编译/反汇编/二进制/pe/elf/mach-o/dll/exe/unpack/ghidra/ida/radare | reverse | reverse-engineering |
| 180 | 外挂/修改器/trainer/cheat/作弊/反作弊/esp/自瞄/透视/开挂 | game-security | game-cheat |
| 175 | apk/android/smali/jadx/frida/ipa/移动端安全 | mobile | mobile-reverse |
| 170 | 固件/firmware/iot/binwalk/路由器/嵌入式 | firmware | reverse-engineering |
| 165 | 恶意软件/malware/病毒/yara/木马/免杀/远控/shellcode/rat/勒索 | malware-ir | reverse-engineering |
| 160 | 内存取证/memory forensics/volatility/进程转储 | memory | dynamic-instrumentation |
| 155 | 渗透/pentest/漏洞验证/攻击面/红队/sql注入/xss/ssrf/rce | pentest | pentest-tools |
| 150 | api/graphql/rest/接口安全/接口测试 | api | pentest-tools |
| 140 | 代码审计/sast/sca/依赖漏洞 | code-security | pentest-tools |
| 130 | fuzz/模糊测试/afl/libfuzzer | fuzzing | reverse-engineering |
| 125 | 爬虫/抓取/采集/scrape | scraper | （直接用 browser_use 工具） |
| 120 | 浏览器自动化/网页操作 | browser | （直接用 browser_use 工具） |
| 110 | github/查资料/调研/官方文档 | research | （直接用 browser_use + shell） |
| 105 | 软件/代码/开发/实现/修复/重构/测试 | software | （Eta 标准工具链） |
| 185 | ida/ida-pro/反编译/hex-rays | ida | ida-reverse |
| 175 | frida/hook/动态注入/内存修改 | frida | dynamic-instrumentation |
| 170 | 协议/protobuf/tlv/抓包/流量分析 | protocol | protocol-reversing |
| 155 | 网络/端口/nmap/扫描 | network | network-pentest |
| 185 | pe/elf/二进制结构/节表/导入表 | binary | binary-analysis |

## 路由规则

1. 提取用户请求中的对象/动作/产出
2. 按优先级从高到低匹配关键词
3. 命中后输出路由回执：`[路由] workflow=<name> | skill=<skill-id>`
4. 多个关键词命中时取优先级最高者
5. 未命中任何关键词时走 Eta 标准工具链处理

## Eta 环境说明

本技能包运行在 Eta Agent Runtime 上，与桌面版的关键差异：

| 能力 | 桌面版 | Eta |
|------|--------|-----|
| Shell | PowerShell / bash | Android root shell + Alpine Linux |
| 包管理 | winget / brew / apt | apk add (Alpine) / 手动下载 |
| Docker | 可用 | 不可用，直接在 Alpine 安装 |
| Python | 系统自带 | Alpine `apk add python3` |
| 工具安装 | 全局安装 | Alpine 环境内 `apk add` 或 `pip install` |
| 文件路径 | Windows/macOS 路径 | Android 内部存储 / Alpine /data |
| ADB | 外部工具 | 可直接 `adb shell`（本机） |
| Frida | pip install | Alpine `pip install frida-tools` |

## Alpine Linux 工具安装

Eta 的 Alpine 环境提供完整 Linux 工具链。安装命令：

```bash
# 基础工具
apk add python3 py3-pip git gcc g++ make cmake

# 安全工具
apk add nmap nmap-scripts
apk add binutils file strace ltrace
pip install frida-tools
pip install angr capstone z3-solver

# 逆向工具（需手动下载二进制）
# jadx: 从 GitHub Release 下载
# apktool: 从 GitHub Release 下载 JAR
# radare2: apk add radare2 或从源码编译
```

## 与 Eta 原生工具的配合

- **shell 工具**: 执行 Android root shell 或 Alpine Linux 命令
- **file 工具**: 读取/写入/列出文件
- **device 工具**: 设备状态、系统信息
- **browser_use**: 网页研究、文档查阅
- **read_image**: 分析截图、APK 界面

## 参考资源

- [routing-rules.json](references/routing-rules.json) — 完整路由规则（JSON 格式）
