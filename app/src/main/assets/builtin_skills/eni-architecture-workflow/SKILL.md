---
name: eni-architecture-workflow
description: "[DOCUMENTATION ONLY] [仅文档] Architecture inventory, component boundaries, data-flow, threat review, diagrams, and documentation workflow. Use for complete architecture analysis."
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

# Architecture Workflow

Inventory components and interfaces. Map data and control flow, state, dependencies, failure modes, and trust boundaries. Verify every claim against source or runtime evidence. Deliver diagrams and an actionable architecture report.

Persist checkpoints before long runs. Record commands, versions, hashes, evidence paths, assumptions, and verification results. Chain through eni-universal-workflow and finish with delivery.
