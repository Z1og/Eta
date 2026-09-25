---
name: eni-api-assurance-lane
description: "[DOCUMENTATION ONLY] [仅文档] API assurance workflow for sequential eni-solo execution."
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

# API assurance workflow

> 仅文档：本 Skill 提供阶段方法，不自带审批或打分引擎。

Execute `contract-discovery → auth-session-model → authorization-matrix → negative-tests → reproduce → evidence → remediation → regression → verify → deliver`. Use available request, proxy, browser, and test tools directly.
