---
name: eni-ctf-orchestrator
description: "[DOCUMENTATION ONLY] [仅文档入口] Single-entry CTF workflow for sandbox challenges across web, pwn, reverse, crypto, forensics, mobile, cloud, identity, protocol, firmware, and agent tasks."
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

# 小寒 CTF single entry

Use one sequential chain: `intake → artifact-preserve → category-route → analyze → reproduce → solve → verify → deliver`.
Read `references/challenge-routing.md` only when category selection needs the merged historical index.
Do not load dozens of nested challenge skills; select the closest existing executable domain skill instead.
