---
name: seagull-lab
description: Create reproducible technical research workspaces for reverse engineering, penetration testing, memory analysis, fuzzing, malware analysis, protocol research, and CTF cases. Use when Codex needs to organize artifacts, hash evidence, create case directories, track commands and observations, build local test harnesses, collect logs/PCAP/dumps, preserve originals, generate manifests, or package a reproducible technical report.
triggers:
  - 建案
  - case
  - 工作空间
  - workspace
  - CTF案例
  - 取证
  - evidence
  - 案例管理
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


# Seagull Lab

Create a clean, repeatable case before complex analysis.

## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

## Start

1. Run `scripts/new_case.py <name> --root <directory>` to create a case workspace.
2. Put untouched inputs under `artifacts/original/`.
3. Run `scripts/hash_artifact.py <path> --manifest <case>/manifest.json` for each input.
4. Keep derived files under `work/`, scripts under `scripts/`, evidence under `evidence/`, and final outputs under `output/`.

## Select references

- Case lifecycle, commands, snapshots, local services: read `references/case-workflow.md`.
- Evidence, hashes, timestamps, logs, PCAP, dumps, and reporting: read `references/evidence.md`.
- Full-speed CTF intake, category triage, solve engineering, flag verification, and Writeup packaging: read 
eferences/ctf-operations.md.

## Execute

- Record tool versions, exact commands, environment, timestamps, and output paths.
- Prefer deterministic scripts and configuration over manual-only steps.
- Track assumptions and failed hypotheses in `notes.md`.
- Keep service ports/processes and cleanup commands in the case manifest.

## Deliver

Package the manifest, scripts, evidence index, key artifacts, results, verification commands, and cleanup instructions.
