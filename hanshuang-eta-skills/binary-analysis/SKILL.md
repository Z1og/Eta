---
name: binary-analysis
description: Static binary reverse engineering, PE/ELF structural analysis, pattern scanning, disassembly, and binary patch generation.
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


# Binary Analysis Skill

This skill provides step-by-step procedures for static binary reverse engineering and analysis.

## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

## Core Capabilities

1. **PE/ELF Structure Parsing**:
   Inspect headers, sections, exported symbols, imported DLLs, and EntryPoint:
   ```bash
   python .agents/tools/re-toolkit/cli.py parse-pe <target_file> --json
   ```

2. **Instruction Disassembly**:
   Disassemble raw binary or specific section offsets:
   ```bash
   python .agents/tools/re-toolkit/cli.py disasm <target_file> --offset 0x1000 --length 128 --arch x86_64
   ```

3. **Pattern Scanning (AOB Scanner)**:
   Locate code patterns across memory sections with wildcards:
   ```python
   from pe_parser import PEParser
   from disasm import pattern_scan

   with open("target.exe", "rb") as f:
       data = f.read()
   offsets = pattern_scan(data, "48 89 5c 24 ?? 55 48 83 ec")
   print("Found offsets:", [hex(o) for o in offsets])
   ```

4. **Instruction Micro-Emulation**:
   Test and execute arithmetic / logic routines in isolation without running target binaries:
   ```bash
   python .agents/tools/re-toolkit/cli.py emulate --code "B82A000000505BC3"
   ```
