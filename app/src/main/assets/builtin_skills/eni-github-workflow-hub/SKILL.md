---
name: eni-github-workflow-hub
description: "Official GitHub workflow source catalog and local tool readiness adapter. Use when selecting upstream methods for reverse engineering, web testing, fuzzing, code security, cloud, mobile, memory, or scraping."
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

# 小寒 GitHub Workflow Hub

Read references/github-sources.md for the curated source matrix. Use scripts/source_catalog.py to filter sources by workflow and scripts/tool_adapter.py to detect locally available adapters.

    python scripts/source_catalog.py --workflow reverse --json
    python scripts/tool_adapter.py --workflow fuzzing --json

The package records immutable reviewed commits and absorbs method structure only. It does not vendor upstream repositories. Prefer stable releases or immutable commits when installing a tool.
