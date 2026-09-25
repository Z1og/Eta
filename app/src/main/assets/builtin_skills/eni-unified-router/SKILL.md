---
name: eni-unified-router
description: "Deterministic eni-solo router. Use at the start of every substantive prompt to select exactly one workflow, print its stages, and load one primary Skill."
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

# eni-solo router

Run `python scripts/router.py --prompt "<complete user prompt>" --json` before task execution.
Print `route_receipt` as the first visible reply line. Execute the returned stages in order and mark each transition as `[STAGE] <stage>`.
Select exactly one workflow. There are no worker splits, approval gates, joins, or automatic upgrade generations.
