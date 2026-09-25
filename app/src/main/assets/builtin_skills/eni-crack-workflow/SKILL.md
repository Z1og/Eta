---
name: eni-crack-workflow
description: "[DOCUMENTATION ONLY] [仅文档] Owned crackme, validation logic, license research, compatibility patch, and challenge workflow using preserved copies, static analysis, dynamic traces, and regression evidence."
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

# Crack Workflow 2.1

Preserve and hash the original; modify copies only. Locate input acquisition, parsing, transformations, comparisons, failure paths, and success paths. Model the validation algorithm from data-flow and cross-references. Correlate runtime traces with static hypotheses. Prefer deriving the expected input or documenting the algorithm; when a patch is part of the local challenge, patch a copy and record offsets and bytes. Re-run success, failure, edge, and restart cases. Deliver recovered logic, reproduction steps, changed artifact hash, and regression evidence.
