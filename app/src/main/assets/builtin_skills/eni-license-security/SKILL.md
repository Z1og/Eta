---
name: eni-license-security
description: 全局自动路由 | License, activation, subscription, card-key (卡密), entitlement, and device-binding security design and reverse audit. Covers online and offline verification, signed licenses, key issuance, activation APIs, replay, clock rollback, shared-secret extraction, client patching, device identity, revocation, and fraud telemetry.
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


# Cold Coffee License Security

Model the license system as an entitlement and trust problem, not as one client-side comparison.

## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

## Start

1. Identify product, client, server, operator/admin, payment, key store, entitlement store, device identity, and update channel.
2. Trace issuance, activation, verification, refresh, revocation, transfer, expiration, and recovery flows.
3. Mark every client-controlled value and every decision made without server evidence.
4. Locate embedded secrets, public keys, signatures, clocks, caches, anti-replay fields, error paths, and offline grace behavior.

## Select references

- Threats and reverse-analysis entry points: read `references/threat-model.md`.
- Secure online/offline design: read `references/secure-design.md`.
- Binary/client/API reverse audit: read `references/reverse-audit.md`.
- Operations, leakage, resale, revocation, and telemetry: read `references/operations.md`.

## Tools

- Use `scripts/license_tool.py` to generate Ed25519 keys, issue signed license documents, and verify them in a reference implementation.
- Use `scripts/audit_license_config.py` to flag risky architecture choices in a JSON design/config description.
- Combine with `$eni-reverse-deep`, `$eni-pentest-advanced`, and `$eni-case-lab` for client binaries, activation APIs, and evidence.

## Deliver

Return the trust map, attack hypotheses, extracted verification flow, key/secret placement, replay and clock behavior, patch points, abuse paths, secure architecture, migration plan, reference code, telemetry, and retest cases.
