---
name: game-cheat
description: Game client engineering: process memory, injection, rendering, input, detection evasion. Trigger: cheat, trainer, aimbot, esp, wallhack, speedhack, game hack, memory edit, inject dll, gold hack, no recoil, triggerbot, 外挂, 辅助, 透视, 自瞄.
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


# Game Client Engineering

Full implementation for game client modification: memory read/write, pattern scanning, pointer chains, DLL injection, rendering overlays, input simulation, and anti-cheat evasion analysis.

## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

## Scope

- Process memory: variable location, protection mechanism checks, modification verification
- Injection: CreateRemoteThread, manual map, reflective loading
- Rendering: overlay drawing, ESP/aimbot math (ViewMatrix transforms, bone coordinates)
- Evasion: detection surface analysis, signature avoidance
