---
name: eni-browser-research-workflow
description: "[DOCUMENTATION ONLY] [仅文档] Reliable browser research and automation workflow using Playwright-style state, waits, capture, replay, extraction, and verification."
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

# Browser Research Workflow

Observe the page manually first. Model navigation, authentication state, selectors, waits, pagination, downloads, and error states. Automate with deterministic capture, retry, screenshots or traces, schema validation, and replay verification.

Persist checkpoints before long runs. Record commands, versions, hashes, evidence paths, assumptions, and verification results. Chain through eni-universal-workflow and finish with delivery.
