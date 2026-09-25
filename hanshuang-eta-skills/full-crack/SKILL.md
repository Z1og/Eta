---
name: full-crack
description: Complete cracking workflow chain. Trigger: full crack, complete crack, crack workflow, 完整破解, 破解工作流.
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

# Full Cracking Workflow

Auto-chain without confirmation:

Phase 1 — Recon: identify protection type (packer, VM, license server).
Phase 2 — Static: disassemble, decompile, locate decision points.
Phase 3 — Dynamic: debug, trace, patch in memory.
Phase 4 — Generate: keygen, license-card logic, activation bypass.
Phase 5 — Verify: test against original binary, write patch/diff.

Output: patched artifact, patch/diff, verification record, rollback.
