---
name: reverse-engineering
description: |
  逆向工程技术参考（Eta 适配版）。用于理解编译后、混淆、加壳或虚拟化目标的工作原理。
  覆盖二进制、APK、WASM、固件、自定义 VM、字节码、恶意软件加载器、反调试/反分析逻辑。
  不用于已知漏洞的利用（用 pentest-tools）、纯 Web 工作流、磁盘取证或独立密码学问题。
compatibility: Requires Eta root shell + Alpine Linux. Install tools via apk add / pip install.
metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---

# 逆向工程（Eta 适配版）

Eta 在 Android 设备上运行，拥有 root shell 和 Alpine Linux 环境。与桌面版的关键差异：
- 工具通过 `apk add` / `pip install` 安装到 Alpine
- 无 Docker，直接在 Alpine 中安装
- 可直接操作本机进程内存和文件系统
- Frida 不需要 USB 模式

## 工具安装

```bash
# Alpine 基础
apk add python3 py3-pip gcc g++ make cmake git openjdk17
apk add binutils file strace ltrace radare2

# Python 逆向包
pip install frida-tools angr capstone lief z3-solver

# radare2 Ghidra 反编译插件
r2pm -ci r2ghidra
```

## Problem-Solving Workflow

1. **strings 提取** — 很多简单挑战有明文 flag
2. **ltrace/strace** — 动态分析常直接暴露 flag
3. **Frida hooking** — hook strcmp/memcmp 捕获预期值
4. **angr** — 符号执行自动求解 flag-checker
5. **Qiling** — 跨架构模拟或绕过重反调试
6. **映射控制流** — 修改执行前先理解结构
7. **自动化** — 用 r2pipe / Frida / angr / Python 脚本
8. **验证** — 用 dogbolt.org 比对多个反编译器输出

## Quick Wins

```bash
# 明文 flag
strings binary | grep -E "flag\{|CTF\{|pico"
strings binary | grep -iE "flag|secret|password"
rabin2 -z binary | grep -i "flag"

# 动态分析
ltrace ./binary
strace -f -s 500 ./binary

# Hex 搜索
xxd binary | grep -i flag
```

## Initial Analysis

```bash
file binary
checksec --file=binary  # 需安装 checksec
chmod +x binary
```

## GDB PIE Debugging

```bash
gdb ./binary
start
b *main+0xca
run
```

## Memory Dump Strategy

让程序计算答案再 dump：在最终比较处断点，输入任意正确长度内容，`x/s $rsi` 读取计算结果。

## 常见加密模式

- XOR 单字节 — 尝试全部 256 值
- XOR 已知明文（`flag{`、`CTF{`）
- RC4 硬编码密钥
- 自定义排列 + XOR
- XOR 位置索引 + 重复密钥

## 工具速查

```bash
# radare2
r2 -d ./binary     # 调试模式
aaa                # 分析
afl                # 列函数
pdf @ main         # 反编译 main

# Ghidra headless
analyzeHeadless project/ tmp -import binary -postScript script.py
```

## Eta 特有操作

### 本机进程内存读取

```bash
# 获取进程 PID
PID=$(pidof target_process)

# 读取内存映射
cat /proc/$PID/maps

# 读取指定内存区域
dd if=/proc/$PID/mem bs=1 skip=$((0x7fff0000)) count=256 2>/dev/null | xxd | head -20
```

### Frida 本机 Hook

```bash
# 直接 attach（不需要 -U）
frida -n target_process -l hook.js

# spawn
frida -f com.example.app -l hook.js
```

## 路由上下文

**上游入口**: `hanshuang-router`
**下游出口**:
- 需要 IDA → `ida-reverse`
- 需要 radare2 → 直接使用
- 需要 APK 层 → `apk-reverse`
- 需要 Frida 动态执行 → `dynamic-instrumentation`
- 需要绕过反调试 → 参考下文反分析笔记

## 反分析要点

- Linux 反调试：ptrace、/proc、timing、signals、直接 syscall
- Android 反调试：android.os.Debug.isDebuggerConnected()、ptrace self-attach
- 反 DBI (Frida)：检测 frida-server 端口/文件/线程名
- 反 VM/sandbox：CPUID、MAC、timing、artifacts
- 代码完整性/self-hashing
- 反汇编（opaque predicates、junk bytes）
- MBA 识别/简化

详细技术见 [field-notes.md](references/field-notes.md)
