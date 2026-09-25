---
name: dynamic-instrumentation
description: Frida dynamic hooking, memory patching, API parameter tracing, and anti-debug bypass script generation.
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


# Dynamic Instrumentation Skill

This skill provides procedures for dynamic binary instrumentation, API hooking with Frida, and runtime memory inspection.

## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

## Core Capabilities

1. **Function Hook Generator**:
   Generate Frida Interceptor scripts for specific functions with argument logging and return value rewriting:
   ```bash
   python .agents/tools/re-toolkit/cli.py gen-hook --symbol <function_name> --module <module_name> --args-count 4 --output hook.js
   ```

2. **Anti-Debug Bypass Generation**:
   Generate ready-to-use Frida bypasses for `IsDebuggerPresent`, `CheckRemoteDebuggerPresent`, and `NtQueryInformationProcess`:
   ```bash
   python -c "from frida_bridge import FridaScriptGenerator; print(FridaScriptGenerator.generate_anti_debug_bypass())"
   ```

3. **Memory Byte Patching**:
   Generate in-memory runtime patches:
   ```bash
   python -c "from frida_bridge import FridaScriptGenerator; print(FridaScriptGenerator.generate_memory_patch('target.exe', '0x1000', '9090C3'))"
   ```
