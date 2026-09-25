---
name: eni-fuzzing-workflow
description: "[DOCUMENTATION ONLY] [仅文档] Coverage-guided fuzzing workflow for local or authorized targets, including harnesses, corpus design, sanitizers, campaigns, minimization, triage, and regression."
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

# Fuzzing Workflow

Define the parser or API boundary. Build a deterministic harness, tiny valid seed corpus, dictionary, sanitizer build, and time budget. Run campaigns, minimize crashes, deduplicate root causes, reproduce under a debugger, and convert confirmed crashes into regression tests.

Persist checkpoints before long runs. Record commands, versions, hashes, evidence paths, assumptions, and verification results. Chain through eni-universal-workflow and finish with delivery.
