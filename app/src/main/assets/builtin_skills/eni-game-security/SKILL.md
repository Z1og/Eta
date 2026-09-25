---
name: eni-game-security
description: 全局自动路由 | Defensive game security and cheat research covering external and internal cheat architecture, trainers, memory tampering, ESP/overlay, aim automation, injection and hooks, packet manipulation, anti-cheat telemetry, integrity, Unity IL2CPP, Unreal, incident analysis, and detection validation.
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


# Cold Coffee Game Security

Analyze the abuse path from trust boundary to observable behavior, then implement detection and hardening.

## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

## Start

1. Identify engine, platform, architecture, network model, authoritative state, anti-cheat components, and available artifacts.
2. Classify the technique: external memory, injected/internal, input automation, overlay/ESP, runtime patch, packet/protocol, asset/config, kernel/driver, DMA/hardware, or account/economy abuse.
3. Map required access, modified state, data sources, persistence/lifecycle, and observable artifacts.
4. Reproduce only the minimum behavior needed to validate detection or a defensive hypothesis.

## Select references

- Cheat categories, data flows, and observables: read `references/cheat-architecture.md`.
- Anti-cheat architecture and control placement: read `references/anti-cheat-design.md`.
- Unity/IL2CPP and Unreal analysis: read `references/engine-security.md`.
- Investigation and evidence: read `references/incident-analysis.md`.

## Tools

- Use `scripts/integrity_manifest.py` to create or verify signed-off file hash manifests.
- Use `scripts/telemetry_analyze.py` to summarize player/input telemetry and flag explainable anomaly indicators.
- Combine with `$eni-reverse-deep`, `$eni-memory-forensics`, and `$eni-case-lab` for binaries, runtime state, and evidence handling.

## Deliver

Return the technique classification, trust-boundary failure, required capabilities, observables, reproduction harness, integrity/telemetry checks, false-positive considerations, mitigations, and retest plan. Separate a detection hypothesis from a confirmed cheat artifact.
