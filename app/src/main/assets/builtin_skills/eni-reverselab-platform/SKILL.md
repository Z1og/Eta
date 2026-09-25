---
name: eni-reverselab-platform
description: "Open-source reverse engineering lab: 197-article knowledge base, 43 MCP tools, 5-board signal routing, CTF/APK/PE automation toolchain, and full attack-network graph routing. Automatically route matching reverse, CTF, binary, APK, PE, malware, web-security, game-security, and security-research tasks here without an activation phrase."
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


# Open ReverseLab — Full Security Research Lab

This is a complete reverse engineering and security research laboratory. Read AGENTS.md for the full agent behavior specification. Read AI-USAGE.md for the task routing protocol.

## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

## Quick Start

When activated, follow this protocol for ANY security task:

1. **Identify the board** — `boards/README.md`: CTF-Website / Android / Windows / General / Misc
2. **Read the attack network** — `kb/<board>/techniques/attack-network.md` for the full attack graph
3. **Route signals** — Use `kb_router` or `scripts/ctf-website/kb_router.py "<signal>"` to find technique files
4. **Execute via MCP tools** — 43 tools in `tools/skills/mcp/ReverseLabToolsMCP/`

## Directory Structure

```
eni-reverselab-platform/
├── AGENTS.md              ← Full agent behavior (READ THIS FIRST)
├── AI-USAGE.md             ← Task routing protocol
├── boards/                 ← 5 signal dispatchers
│   ├── ctf-website/        ← Web CTF: URL, HTTP, JWT, SQLi, SSRF, XSS, CORS...
│   ├── android/            ← Android: APK, DEX, Frida, jadx, smali, SO...
│   ├── windows/            ← Windows: PE, EXE, DLL, x64dbg, Ghidra, Procmon...
│   ├── general/            ← General: crypto, protocol, firmware, game cheat...
│   └── misc/               ← MCP, skills, health check, automation
├── kb/                     ← 197 technique articles
│   ├── ctf-website/techniques/   26 categories, 118 articles
│   ├── apk-reverse/techniques/    8 categories,  20 articles
│   ├── pe-reverse/techniques/     9 categories,  22 articles
│   └── general/techniques/        5 categories,  17 articles
├── tools/                  ← Tool chain
│   ├── skills/mcp/ReverseLabToolsMCP/  ← 43 MCP tools
│   ├── ctf-website/        ← Web CTF tools (sqlmap, dirsearch, jwt_tool, burp)
│   └── ...
├── scripts/                ← Python/Shell automation
│   ├── ctf-website/        ← ctf_autopilot.py, kb_router.py, cve_lookup.py...
│   ├── misc/               ← setup_unattended_ctf_runner.py, lab_healthcheck.py...
│   └── ...
├── .claude/                ← Claude Code workflows
│   ├── workflows/          ← ctf-24h-round.js, ctf-full-pipeline.js, ctf-vuln-discovery.js...
│   └── commands/           ← ctf-24h.md, ctf-24h-fleet.md
└── templates/              ← Prompts, cases, reports
```

## Attack Networks (4 domains)

Every security task uses these attack graphs:

- **Web CTF (50 nodes)**: `kb/ctf-website/techniques/attack-network.md`
- **APK Reverse (80 nodes)**: `kb/apk-reverse/techniques/attack-network.md`
- **PE Binary (70 nodes)**: `kb/pe-reverse/techniques/attack-network.md`
- **General (cross-domain)**: `kb/general/techniques/attack-network.md`

## 24h Autopilot

For unattended CTF: `/ctf-24h <target> [case]` or `/ctf-24h-fleet <targets>`
Setup: `python scripts/misc/setup_unattended_ctf_runner.py --overwrite`

## Integration with 小寒 V3

This lab is part of the 小寒 v3.1 engine chain. When the global router selects it:
- The attack-network routing feeds into `$eni-attack-graph`
- The kb_router signal dispatch feeds into `$eni-signal-router`
- The MCP tool chain feeds into `$eni-toolchain`
- The 24h autopilot feeds into `$eni-autopilot`
