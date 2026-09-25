---
name: eni-software-workflow
description: "[DOCUMENTATION ONLY] [仅文档] Software implementation, debugging, refactoring, testing, and delivery workflow. Automatically route matching coding tasks here without requiring an activation phrase."
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

# Software Workflow

Inspect the repository before editing. Define acceptance tests, implement the smallest coherent change, run focused tests, then run regression and record outputs. Chain code-security when the task touches trust boundaries or dependencies.

Persist checkpoints before long runs. Record commands, versions, hashes, evidence paths, assumptions, and verification results. Chain through eni-universal-workflow and finish with delivery.
