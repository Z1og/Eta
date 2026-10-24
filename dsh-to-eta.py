#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dsh-to-eta.py —— 把 dsh-purge-allowed 的红队六角色提示词适配成 Eta 技能。

源：<repo>/lib/redteam/store-core.js 的 DEFAULT_PROMPTS（plan/recon/assess/vuln-scan/exploit/internal），
    正文末尾拼接 COMMON_ENV / COMMON_BIND / COMMON_SCORE_RULES / COMMON_DB_LOOKUP /
    COMMON_EVIDENCE / COMMON_HANDOFF 六段公共文本。

适配要点：DSH 版把台账做成了 33 个 redteam_* 内置工具，Eta 没有。这里用 bash + sqlite3
写了一支等价 CLI（scripts/rt.sh，随技能一起分发），并把提示词里的工具名替换成 rt 子命令，
再补一段「Eta 环境与工具映射」说明，其余方法论原文保留。

用法：
    python dsh-to-eta.py --source "C:\\path\\to\\dsh-purge-allowed" --builtin
    python dsh-to-eta.py --source ... --dry-run          # 只打印统计，不写文件
"""

import argparse
import json
import os
import re
import shutil
import sys

Eta = "app/src/main/assets/builtin_skills"
STAGE = "dist/dsh-adapt"

ROLES = {
    "plan": dict(
        skill="dsh-redteam-plan",
        name="红队指挥（主会话）",
        desc="红队作战指挥角色：把目标拆成任务、派给执行角色、汇总成果并决定下一步；"
             "用户只给单位名时按 信息收集→资产梳理→漏洞发现→漏洞利用→内网渗透 顺序推进。",
        triggers="红队, 红队指挥, 打点, 攻击流程, redteam, 派活, 拉智能体",
    ),
    "recon": dict(
        skill="dsh-redteam-recon",
        name="信息收集（互联网侧）",
        desc="红队信息收集角色：子域枚举、证书透明、测绘平台、端口与服务指纹，"
             "把目标单位的互联网资产逐条落台账，供后续资产梳理与漏洞发现使用。",
        triggers="信息收集, 子域枚举, 资产收集, 测绘, fofa, 端口扫描, recon",
    ),
    "assess": dict(
        skill="dsh-redteam-assess",
        name="资产梳理（易打性评估）",
        desc="红队资产梳理角色：逐条评估已收集资产的易打性、标记优先级与可疑面，"
             "写回台账并交给漏洞发现角色，不自行验证漏洞也不打分。",
        triggers="资产梳理, 易打性, 优先级, 面评估, 台账, assess",
    ),
    "vuln-scan": dict(
        skill="dsh-redteam-vuln-scan",
        name="漏洞发现",
        desc="红队漏洞发现角色：优先 Nday/1day 已知漏洞，再打接口未授权与逻辑缺陷，"
             "每条发现带请求证据落台账，交漏洞利用角色。",
        triggers="漏洞发现, 漏洞扫描, nday, 未授权, 指纹匹配, nuclei, vuln-scan",
    ),
    "exploit": dict(
        skill="dsh-redteam-exploit",
        name="漏洞利用",
        desc="红队漏洞利用角色：把已确认漏洞打成实际权限（WebShell / 命令执行 / 数据库），"
             "登记后台会话、隧道与凭据并证明可用。",
        triggers="漏洞利用, 拿权限, getshell, webshell, 后台登录, exploit",
    ),
    "internal": dict(
        skill="dsh-redteam-internal",
        name="内网渗透",
        desc="红队内网渗透角色：借已打通的隧道做内网横向、凭据复用与集权系统突破，"
             "把内网资产与新入口继续落台账。",
        triggers="内网渗透, 横向, 隧道, 凭据复用, 集权系统, internal",
    ),
}

# redteam_* 内置工具 → rt 子命令
TOOL_MAP = {
    "redteam_asset_add": "rt asset add",
    "redteam_asset_query": "rt asset query",
    "redteam_asset_get": "rt asset get",
    "redteam_asset_test": "rt asset test",
    "redteam_asset_assess": "rt asset assess",
    "redteam_asset_stats": "rt asset stats",
    "redteam_vuln_add": "rt vuln add",
    "redteam_vuln_query": "rt vuln query",
    "redteam_vuln_update": "rt vuln update",
    "redteam_chain_add": "rt chain add",
    "redteam_credential_add": "rt cred add",
    "redteam_credential_list": "rt cred list",
    "redteam_webshell_add": "rt shell add",
    "redteam_webshell_list": "rt shell list",
    "redteam_tunnel_add": "rt tunnel add",
    "redteam_tunnel_list": "rt tunnel list",
    "redteam_tunnel_update": "rt tunnel update",
    "redteam_access_add": "rt access add",
    "redteam_access_list": "rt access list",
    "redteam_session_check": "rt session check",
    "redteam_sessions": "rt session list",
    "redteam_score_hit": "rt score hit",
    "redteam_score_list": "rt score list",
    "redteam_poc_add": "rt poc add",
    "redteam_poc_get": "rt poc get",
    "redteam_poc_search": "rt poc search",
    "redteam_http_evidence_add": "rt evidence add",
    "redteam_shot_add": "rt evidence add",
    "redteam_attack_file_add": "rt file add",
    "redteam_attack_chain": "rt chain list",
    "redteam_preflight": "rt preflight",
    "redteam_agent_slot": "spawn_agents",
}

ENV_SECTION = """## 运行环境与工具映射（Eta 版）

本技能原为 DSH 红队插件的角色提示词，移植到 Eta 后工具面换成下面这套：

- **台账**：`scripts/rt.sh`（bash + sqlite3），数据落在 `$RT_HOME/rt.db`（默认 `$HOME/redteam/rt.db`）。
  首次使用先 `apk add sqlite`，然后 `bash scripts/rt.sh preflight` 自检。
- 提示词里出现的 `rt asset add` / `rt score hit` 等，都是这支 CLI 的子命令，
  参数统一用 `--key=value`，例如：
  `bash scripts/rt.sh asset add --ip=10.1.2.3 --port=443 --url=https://x --title=后台`
  `bash scripts/rt.sh chain add --agent=recon --stage=recon --tool="fscan -h 10.1.2.3" --result="开放 22/443"`
- **子智能体**：用 Eta 的 `spawn_agents` 派活；`redteam_agent_slot` 的"名额"只是参考，不拒绝派活。
- **命令执行**：`terminal`（Android root shell / Alpine）。扫描器缺失时先 `apk add` 或 pip 装，
  装不上就用现有工具降级推进并说明降级范围。
- **截图/证据**：设备截图存文件后登记 `rt evidence add --asset=<id> --path=<文件> --note=<证明什么>`。
- **技能加载**：需要别的技能时用 `skills_list` / `skills_read` 读取对应 SKILL.md。

"""

FM = """---
name: {skill}
description: >-
  {desc}
compatibility: Requires Eta root shell + Alpine Linux（台账需要 sqlite3）。
metadata:
  source: dsh-purge-allowed
  upstream-role: {role}
  upstream-version: {version}
  eta-env: alpine-linux
triggers: {triggers}
---

# {title}

"""


def js_unescape(text: str) -> str:
    """JS 模板字符串里的转义（\\n / \\t / \\` / \\\\ / \\$）还原成原文。"""
    table = {"n": "\n", "t": "\t", "r": "\r", "`": "`", "\\": "\\", "$": "$", '"': '"', "'": "'"}
    out, i = [], 0
    while i < len(text):
        if text[i] == "\\" and i + 1 < len(text):
            out.append(table.get(text[i + 1], text[i:i + 2]))
            i += 2
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def parse_templates(src: str):
    """取出所有模板字符串常量（const NAME = 反引号 ... 反引号）。"""
    consts = {}
    for m in re.finditer(r"^const ([A-Z][A-Z0-9_]*)\s*=\s*`", src, re.M):
        name = m.group(1)
        start = src.index("`", m.end() - 1) + 1
        i, buf = start, []
        while i < len(src):
            ch = src[i]
            if ch == "\\":
                buf.append(src[i:i + 2])
                i += 2
                continue
            if ch == "`":
                break
            buf.append(ch)
            i += 1
        consts[name] = js_unescape("".join(buf))
    return consts


def parse_prompts(src: str, consts: dict):
    i = src.index("export const DEFAULT_PROMPTS")
    brace = src.index("{", i)
    depth, j, in_tick = 0, brace, False
    while j < len(src):
        ch = src[j]
        if in_tick:
            if ch == "\\":
                j += 2
                continue
            if ch == "`":
                in_tick = False
            j += 1
            continue
        if ch == "`":
            in_tick = True
            j += 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    block = src[brace:j + 1]

    prompts = {}
    # 键可能是裸标识符（plan）或带引号（'vuln-scan'）
    for m in re.finditer(r"""['"]?([\w-]+)['"]?\s*:\s*`""", block):
        role = m.group(1)
        start = m.end()
        k, buf = start, []
        while k < len(block):
            ch = block[k]
            if ch == "\\":
                buf.append(block[k:k + 2])
                k += 2
                continue
            if ch == "`":
                break
            buf.append(ch)
            k += 1
        text = js_unescape("".join(buf))
        text = re.sub(r"\$\{(\w+)\}", lambda mm: consts.get(mm.group(1), mm.group(0)), text)
        prompts[role] = text
    return prompts


def adapt_text(role: str, text: str) -> str:
    for tool, repl in sorted(TOOL_MAP.items(), key=lambda kv: -len(kv[0])):
        text = text.replace(tool, repl)
    text = text.replace("spawn_teammate", "spawn_agents")
    # 原句是 `redteam_agent_slot`（action=status）只统计…，换成 Eta 的说法
    text = text.replace("`spawn_agents`（action=status）", "`spawn_agents` 的并发统计")
    text = text.replace("$DSH_HOME/redteam/setup.sh", "scripts/rt.sh")
    text = text.replace("$DSH_HOME", "$RT_HOME")
    text = text.replace("redteam_setup", "dsh-redteam-setup")
    # 正文里已经有一段旧环境说明（COMMON_ENV），换成 Eta 版
    text = re.sub(r"## 运行环境（所有角色都遵守）.*?(?=\n## |\Z)", "", text, flags=re.S)
    return ENV_SECTION + text.strip() + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", required=True, help="dsh-purge-allowed 仓库根目录")
    ap.add_argument("--builtin", action="store_true", help="写入 app/src/main/assets/user_protocol/redteam-mode.md")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    store = os.path.join(args.source, "lib", "redteam", "store-core.js")
    if not os.path.isfile(store):
        print(f"找不到 {store}", file=sys.stderr)
        return 1
    src = open(store, encoding="utf-8").read()
    consts = parse_templates(src)
    prompts = parse_prompts(src, consts)
    missing = [r for r in ROLES if r not in prompts]
    if missing:
        print(f"缺少角色: {missing}", file=sys.stderr)
        return 1

    pkg = json.load(open(os.path.join(args.source, "package.json"), encoding="utf-8"))
    version = pkg.get("version", "unknown")

    sections = [
        f"# 红队模式协议（源自 dsh-purge-allowed v{version}）",
        "",
        "## 使用方式",
        "",
        "- 你是主会话的**指挥**，按下面「一、指挥」执行；五个执行角色是你要派出去的分工。",
        "- 派子代理时，把「二」～「六」中对应角色的整段正文作为 `spawn_agents` 任务说明的前缀，"
        "再接上本次具体目标（目标范围、已知信息、已经测过什么、期望产出）。",
        "- 台账统一走 `scripts/rt.sh`（bash + sqlite3，`$RT_HOME/rt.db`）；"
        "没装脚本时用 `sqlite3` 按各角色「落库」小节里的字段自己建表。",
        "- 报告与交付口径沿用本协议里的公共要求；只写实际看到回显、实际登录成功、实际跑通的东西。",
        "",
    ]
    order = ["plan", "recon", "assess", "vuln-scan", "exploit", "internal"]
    cn = {
        "plan": "一、指挥（主会话）",
        "recon": "二、信息收集（recon）",
        "assess": "三、资产梳理（assess）",
        "vuln-scan": "四、漏洞发现（vuln-scan）",
        "exploit": "五、漏洞利用（exploit）",
        "internal": "六、内网渗透（internal）",
    }
    for role in order:
        body = adapt_text(role, prompts[role])
        sections.append(f"## {cn[role]}")
        sections.append("")
        sections.append(body.strip())
        sections.append("")
        print(f"{role:9s} {len(body)} chars")

    protocol = "\n".join(sections).strip() + "\n"
    print(f"协议总长 {len(protocol)} chars")

    if args.dry_run:
        return 0

    os.makedirs(STAGE, exist_ok=True)
    open(os.path.join(STAGE, "redteam-mode.md"), "w", encoding="utf-8").write(protocol)
    if args.builtin:
        dest = "app/src/main/assets/user_protocol/redteam-mode.md"
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        open(dest, "w", encoding="utf-8").write(protocol)
        print(f"写入 {dest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
