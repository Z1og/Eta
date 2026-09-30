---
name: anti-frida-bypass
description: |
  反 Frida / 反调试检测面汇总与绕过方法论：maps/端口/线程名/TracerPid/时间侧信道/签名校验
  等检测点的定位与规避，配合硬件断点（hw-trace）、gadget 改名、线程伪装、SELinux/侧加载策略。
  当目标 App 闪退、检测到 frida-server、gum-js-loop、re.frida、调试器附加检测时使用。
triggers: 反frida, 反调试, 检测frida, frida检测, 绕过检测, 反hook, 防调试, gum-js-loop, TracerPid, 反注入, 隐藏frida, frida隐藏
---

# 反 Frida / 反调试对抗

目标：让 Frida 注入与调试在强检测目标上活下来。顺序：**先定位检测点 → 最小改动规避 → 顽固点上硬件层（hw-trace）**。

## 一、检测面清单（先逐一核对）

| 检测点 | 原理 | 自查命令（root） |
|---|---|---|
| `/proc/<pid>/maps` 特征库 | 扫描 frida-agent / gum-js-loop / gadget so | `cat /proc/$(pidof PKG)/maps \| grep -iE 'frida\|gadget\|gum'` |
| 端口 27042 | frida-server 默认监听 | `cat /proc/net/tcp \| grep 69A2`（27042=0x69A2） |
| 线程名 | `gum-js-loop`、`gmain`、`gdbus` | `ls /proc/$(pidof PKG)/task/*/comm` |
| `TracerPid` | ptrace 附加检测 | `grep TracerPid /proc/$(pidof PKG)/status` |
| 内存特征串 | 扫描 `frida`/`FRIDA`/`re.frida` 字面量 | mem_search 工具搜 `frida` |
| 时间侧信道 | 同函数多次调用耗时对比（注入变慢） | 日志/Timing 观察 |
| 签名/APK 校验 | 重打包后签名不一致 | 对比原签名证书 SHA256 |
| Root/注入环境 | su、magisk、zygisk、LSPosed 模块 | 目标自己的 root 检测日志 |

## 二、规避策略（按检测点对应）

1. **frida-server 侧**：
   - 改名部署（不用 `frida-server` 文件名），换端口：`./fs -l 0.0.0.0:12345`，连接 `frida -H 127.0.0.1:12345`
   - 优先 **gadget 模式**：把 frida-gadget so 注入或打包进 App（无独立 server 进程、无 27042 端口）
   - 移除 maps 特征：gadget 改名 so 文件名；极端时 hook `strstr`/`open("/proc/self/maps")` 过滤特征行
2. **线程/字符串特征**：
   - hook 目标自读取点（`fopen("/proc/self/task/.../comm")`、`maps` 读取）返回清洗后的内容
   - 用脚本抹掉 frida 相关字面量（Java/Native 层字符串替换）
3. **TracerPid / ptrace**：
   - hook `ptrace(PTRACE_TRACEME)` 返回成功但不真 attach
   - hook 读取 `/proc/self/status` 的函数，把 `TracerPid: N` 改成 `0`
4. **签名校验**：
   - 优先不重打包（attach 已有进程）；必须重打包时用原签名或 hook `PackageManager.getPackageInfo(...GET_SIGNATURES)` 返回原证书
5. **时间侧信道**：关键函数做时延补偿桩，或改用硬件断点（不改代码，无注入痕迹）

## 三、硬件层兜底（hw-trace 联动）

软件层对抗不过时：
- 用 `hw-trace` 的硬件断点/观察点（DBGBCR/DBGWCR、GDB `hbreak`/`watch`）——代码零修改，反注入扫不出来
- 用 ETE/TRBE 指令追踪拿到执行流，离线分析检测函数位置与返回值，再精准 hook

## 四、验证闭环

1. 注入前：跑目标正常 → 记录检测日志（logcat 关键词 `frida|debug|inject|protect`）
2. 规避后逐项自查上表，确认特征消失
3. 目标功能点跑通（hook 生效）且不闪退 ≥5 分钟
4. 交付：检测点清单（命中/未命中）+ 规避脚本 + 验证日志

## 五、Eta 环境注记

- frida-server/gadget 经 Alpine 安装：`pip install frida-tools`，server 二进制放 `/data/local/tmp/`
- 内存特征自查用 `mem_search` 工具；进程/线程查看用 `process_list`
- 顽固检测 → `hw-trace` 技能（硬件断点/ETE trace）
