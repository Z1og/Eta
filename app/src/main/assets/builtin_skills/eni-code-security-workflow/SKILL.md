---
name: eni-code-security-workflow
description: "[DOCUMENTATION ONLY] [仅文档] Source, dependency, supply-chain, SAST, SCA, and remediation workflow for operator-owned repositories."
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

# Code Security Workflow

Build a language and dependency inventory. Threat-model entry points. Combine Semgrep-style pattern scans, OSV dependency matching, Trivy-style SBOM and configuration review, and manual data-flow validation. Reproduce each finding, fix a copy or branch, and add regression tests.

Persist checkpoints before long runs. Record commands, versions, hashes, evidence paths, assumptions, and verification results. Chain through eni-universal-workflow and finish with delivery.
