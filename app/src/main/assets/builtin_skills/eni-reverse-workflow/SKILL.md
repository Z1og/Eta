---
name: eni-reverse-workflow
description: "Deep, evidence-driven reverse engineering workflow for PE, ELF, Mach-O, firmware, drivers, bytecode, protocols, and local binaries."
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

# Reverse Workflow 2.1

Preserve the original and work from a hashed copy.

1. Fingerprint format, architecture, entropy, imports, signatures, sections, and packer indicators.
2. Run capa-style capability triage before deep reading.
3. Use Ghidra-style headless analysis for functions, cross-references, call graphs, types, and decompilation.
4. Form explicit hypotheses around data sources, transformations, checks, and sinks.
5. Use Frida-style runtime observation only in a controlled local environment.
6. Correlate static and dynamic evidence, reproduce behavior, and deliver scripts, offsets, symbols, copy patches, or a report.

Chain mobile, firmware, malware-ir, fuzzing, or crack when signals require them.
