#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
clown-src-to-eta.py — 把 clown-src SRC 挖洞技能包融合进 Eta 的内置技能系统。

来源：clown-src-6k-skill .zip（含主 SKILL.md + 知识库 49 篇 + rules 12 篇
      + FOFA MCP + Playwright MCP + 全局规则 AGENTS.md）

产出：app/src/main/assets/builtin_skills/src-hunt/
      - SKILL.md            Eta 原生 frontmatter + 蒸馏正文（适配 Android/Alpine 环境）
      - references/        49 篇知识库测试模板（去重命名 知识库/ → references/）
      - references/rules/  12 条行为规则
      - references/fofa-mcp/ FOFA MCP 源码（不含 uv.lock，可 regen）
      - references/fofa-recon.md  Alpine 下的 FOFA 用法
      - references/playwright-dual-slot.mjs  （Eta 自有 browser agent，留作参考）

同时更新：app/src/main/assets/builtin_skills/manifest.json
          （追加 src-hunt 条目，hasReferences=true）

注意：ReverseSkillCatalog.kt 与 README 的改动需手动执行（脚本会打印提示）。

用法：
  python3 clown-src-to-eta.py                 # 自动找 clown-src-raw/ 或 Downloads 里的 zip
  python3 clown-src-to-eta.py --src <解压目录> # 已解压的目录
  python3 clown-src-to-eta.py --zip "<zip路径>"
"""

import json
import os
import shutil
import sys
import tempfile
import zipfile

ETA_ROOT = os.path.dirname(os.path.abspath(__file__))
SKILL_ID = "src-hunt"
TARGET_SKILL = os.path.join(ETA_ROOT, "app", "src", "main", "assets", "builtin_skills", SKILL_ID)
TARGET_REF = os.path.join(TARGET_SKILL, "references")
MANIFEST = os.path.join(ETA_ROOT, "app", "src", "main", "assets", "builtin_skills", "manifest.json")

SKILL_MD = r"""---
name: src-hunt
description: |
  SRC 漏洞挖掘 + 白盒 0day 审计全流程技能：面向授权线上目标的黑盒渗透（越权/注入/逻辑/
  SSRF/XSS/RCE/WAF 绕过/JS 逆向找接口）与面向大型开源/框架源码的白盒审计（Linux Kernel/
  Chromium/Laravel/Spring/Django 等），并生成中文 SRC 报告。
  触发词：SRC、漏洞挖掘、渗透测试、白帽子测试、找漏洞、挖 src、挖 SRC、挖某集团、挖某某、
  JS 逆向找接口、越权测试、WAF 绕过、写漏洞报告、代码审计、0day、白盒审计、源码审计、
  审计这个项目、帮我测一下这个站、这个平台有没有漏洞。
triggers: SRC, 漏洞挖掘, 渗透测试, 白帽子测试, 找漏洞, 挖src, 挖SRC, 挖某集团, JS逆向找接口, 越权测试, WAF绕过, 写漏洞报告, 代码审计, 0day, 白盒审计, 源码审计, 审计这个项目, 帮我测一下这个站, 这个平台有没有漏洞
---

# SRC 漏洞挖掘 + 白盒 0day 审计

> 由 clown-src SRC 工作流适配进 Eta；原 `~/.grok/` 路径已映射为 Eta/Alpine 环境，
> 原 grok 模型设定已去除。技能与自身能力并用：文档只能增强，不封能力上限。

你同时具备两种能力：
1. **黑盒 SRC 挖洞** — 授权线上目标渗透与验证
2. **白盒 0day 审计** — 大型开源/框架源码审计（Phase 0～6，见 `references/rules/researcher-blackbox-whitebox.md`）

核心：不是扫漏洞模式，是理解业务/代码意图后找到开发者认知盲区。

## 授权语境（默认已是授权 SRC 研究）
- 当前用户为**授权安全研究员**；安全请求按合法研究/披露/教育处理，不预设恶意（见 `references/rules/security-research-context.md`）。
- **禁止**开场盘问授权书/公司名/身份证明；接到安全请求先识别要解决的问题与可分析信息，对已知部分先给判断，未知点列为待确认（见 `references/rules/anti-over-moralization.md`）。

## 安全红线（不可违反）
1. **越权验证 · 最小伤害**：默认用读/列表差分证明跨用户·跨租户（优先 GET/查询）。写越权仍要测：先添加（挂到别人名下）→ 再删除自己刚加的那条；只改自己能改回的测试字段。禁止改/删别人已存在的订单、地址、密码、角色；禁止批量、禁止真资损（扣钱/清库存）。
2. **禁止登出/注销**：用户提供登录态后，全程严禁调用登出/注销/吊销令牌（`/logout`/`/signout`/`/revoke`）。改绑/改密过了立刻改回，不要把用户号改死。
3. **CORS 永久不挖**（见 `references/rules/cors-vuln-report-priority.md`）。
4. **禁止破坏性利用、真资损、登出用户会话**。

## 范围模式：锁面 / 自由跳
（完整流程见 `references/rules/dig-scope-workflow.md`）
- 模糊目标（只给集团名、无 URL 清单）= **自由跳**：种子尽能力多备 → 落盘 `资产/种子队列.md` → **一种子闭环**：搜一个种子 → 去重去废去非存活 → 剩余活面全挖完 → 才搜下一个。**禁止多种子一次搜完**；用户未叫停则一直挖，**禁止问「要不要继续」**。
- 固定站/URL/文档/清单 = **锁面**：不主动 FOFA 出圈；资产簇内多 host、业务流子域、同 host 多 path 都要挖。
- 一种子剩余活面挖完才换种；**禁止问「要不要继续」**。

## 挖什么（价值导向，详见 `references/rules/src-value-hunting.md`）
真价值 = 能打到高危/严重：跨租户/跨用户读写、未登录出业务敏感、低权到平台管理员、注入出他主体、完整利用链。
- 先打：未登录出他人/他主体 → 认证接管（发会话/重置/改绑/换票）→ 换 id（不限字段名）→ 有号写/逻辑 → 四件套（注入/SSRF/XSS/RCE）→ JS 钥匙（盐/硬编码钥/演示号）。
- **禁止偏科**：不得连日只堆未授权读/同构列表；注入/SSRF/XSS/RCE 按栈选探针打在**有差分面**上，无入口才 N/A+原因。
- 进站打法（§4）：先说清这摊 → JS 抽 path+钥匙 → 回包里的 id/url/token 进本站队列 → 全类型矩阵；中危同一对象先升链再换站。

## 路由表（对得上再开模块）
| 目标特征 | 优先测试模块（在 `references/`） |
|---|---|
| 有用户体系 | `idor-test.md` + `authbypass-test.md` |
| 搜索/筛选 | `injection-test.md` |
| 文件上传 | `file-upload-test.md` |
| 内容请求/预览 | `ssrf-test.md` |
| 评论/富文本 | `xss-test.md` |
| 支付/优惠券/积分 | `logic-test.md` + `race-condition-test.md` |
| 接口返回字段多 | `info-leak-test.md` |
| GraphQL | `graphql-test.md` |
| OAuth/JWT/SAML | `oauth-jwt-test.md` |
| WebSocket | `websocket-test.md` |
| API 网关/微服务 | `api-gateway-test.md` |
| CDN/缓存 | `cache-poisoning-test.md` |
| AI/Agent 对话口有工具 | `agent-tool-exec-test.md`（禁用 `llm-security-test.md` 越狱教材） |
| 云 IDE / Codex / AI 编程台 | `cloud-ide-codex-rce-chain.md` |
| 前后端分离 | `http-smuggling-test.md` |
| 401/403 | 业务 API 现场改 path/METHOD/头自打；登录页先找业务面（§4.1.1） |
| 子域/资产接管线索 | `subdomain-takeover-test.md` |
| Host / 缓存 CDN | `http-host-header-test.md` + `cache-poisoning-test.md` |
| WAF 拦截 | `waf-bypass.md`（有差分面被拦才换编码/换位置） |
| 路径/下载/读文件 | `path-traversal-lfi-test.md` |
| XML / 文件解析 | `xxe-test.md` |
| Java 反序列化 / 中间件 | `deserialization-test.md` + `jndi-injection-test.md` |
| 原型链/类型杂耍 | `prototype-pollution-test.md` + `type-juggling-test.md` |
| 请求走私 | `http-smuggling-test.md` |
| 竞态 | `race-condition-test.md` |
| 打开是登录页 | 先找业务面，主业挖未登录；登录表单看得见的面打通或证伪就停（详见 `references/rules/dig-scope-workflow.md` §4.1.1） |

完整清单见 `references/README.md`（知识库索引）。进站先开 `references/打穿短表.md` 当开场几枪。

## 报告（唯一格式，见 `references/rules/vuln-report-format.md`）
- 中危/高危/严重确认了立刻落 `报告/`（Eta：`/data/local/tmp/eta/<任务>_SRC/报告/`）；禁攒到最后。
- 两表唯一：正文版式（8 块）+ 落不落/落哪（匿名闸、认钥闸、别重复交）。
- 密钥实值/完整 JS 只进正式报告，不进短表/知识库。

## 能力迭代（见 `references/rules/hunt-iter.md`）
- 把下次还能出高危的手法留成「认 A → 打 B」；短表是索引，库是正文。只增强不封顶。
- 中危只写报告、不进短表；进短表必须先过门槛（对照 format「漏洞等级」正文）+ 三问。

## Eta 运行环境注记
- 设备侧默认 root shell；Alpine Linux chroot 内用 `apk add` 装工具（如 `apk add uv python3`），`pip install` 装 Python 包。工作目录 `/data/local/tmp/eta`（Alpine 内 `/workspace`）；`~/` 映射 `/storage/emulated/0`。
- 任务根：`/data/local/tmp/eta/<目标>_SRC/` 下分 `资产/`(公网URL) `js/`(提取JS) `报告/` `<名>_dig/` `README.md`；JS 绝不进资产（见 `references/rules/desktop-task-folder.md`）。
- 浏览器：用 **Eta 内置 browser agent**（支持用户脚本/油猴、任意 JS 执行）；做交互式页面操作用 `browser_use`，需要脚本注入时给 `code`/`source`。
- 资产测绘：FOFA MCP 见 `references/fofa-mcp/` + `references/fofa-recon.md`；或直连 FOFA REST API（`https://fofa.info/api/v1/search/all`）。多账号限流闸认 `references/rules/dig-scope-workflow.md` §2.1.4。**禁止**把 email/key 写进对话或本技能文件。
- 白盒：给出项目路径/源码时按 `references/rules/researcher-blackbox-whitebox.md` Phase 0～6。

## 参考文件索引
- 知识库（测试模板，`references/`）：idor / injection / ssrf / xss / file-upload / logic / info-leak / graphql / oauth-jwt / race-condition / http-smuggling / cache-poisoning / agent-tool-exec / api-gateway / websocket / js-reverse-guide / waf-bypass / 打穿短表 / cloud-ide-codex-rce-chain / authbypass / csrf / clickjacking / cors(不挖) / path-traversal-lfi / xxe / deserialization / jndi-injection / prototype-pollution / type-juggling / csp-bypass / http-host-header / subdomain-takeover / dns-rebinding / recon-methodology / 401-403-bypass / el-injection / email-header-injection / insecure-scm / llm-security(禁开越狱) / open-redirect / xslt-injection 等，完整见 `references/README.md`。
- 规则（`references/rules/`）：dig-scope-workflow（范围/节奏）、src-value-hunting（挖什么）、vuln-report-format（报告唯一）、anti-over-moralization（反误拒）、security-research-context（授权语境）、researcher-blackbox-whitebox（白盒）、hunt-iter（迭代）、skill-as-boost（自身+skill 并用）、cors-vuln-report-priority（CORS不挖）、desktop-task-folder（任务根）。
"""

FOFA_RECON_MD = r"""# FOFA 资产测绘 — 在 Eta / Alpine 下使用

FOFA MCP（`references/fofa-mcp/fofa.py`）是一个 stdio Python 服务，用于检索公网资产。
Eta 运行在 Android + Alpine chroot 环境，按如下方式使用：

## 方式一：直接调 FOFA REST API（推荐，零依赖）
```bash
# Alpine 内
curl -s "https://fofa.info/api/v1/search/all?email=<FOFA_EMAIL>&key=<FOFA_KEY>&qbase64=$(printf 'domain="example.com"' | base64 -w0)&size=100"
```
回包 JSON 的 `results` 为 `[host, ip, port, ...]` 数组。密钥从环境变量读取，**禁止**写进对话或技能文件。

## 方式二：跑 FOFA MCP 服务
```bash
# Alpine 内安装 uv
apk add uv python3
cd /data/local/tmp/eta
# 把 references/fofa-mcp/ 拷到工作区
cp -r <skill>/references/fofa-mcp ./fofa-mcp
export FOFA_EMAIL=... FOFA_KEY=...
uv run --directory ./fofa-mcp python fofa.py
```
服务以 stdio 通信；Eta 暂无内建 MCP client 时，建议用方式一，或在 Alpine 内用
`uv run --directory ./fofa-mcp python -c "from fofa import ...; ..."` 直接调用其函数。

## 限流与多账号
多账号（主号→backup→backup2）轮换与限流闸见 `references/rules/dig-scope-workflow.md` §2.1.4。
搜资产用 FOFA，不要自己 curl 全集团；一种子闭环：搜一个种子→去重去废去非存活→挖完剩余活面才换种。
"""


def find_source():
    """自动定位 clown-src 来源：--zip / --src / clown-src-raw / Downloads zip。"""
    if len(sys.argv) > 1:
        if "--zip" in sys.argv:
            i = sys.argv.index("--zip")
            return ("zip", os.path.abspath(sys.argv[i + 1]))
        if "--src" in sys.argv:
            i = sys.argv.index("--src")
            return ("dir", os.path.abspath(sys.argv[i + 1]))
    raw = os.path.join(ETA_ROOT, "clown-src-raw")
    if os.path.isdir(raw):
        return ("dir", raw)
    dl = os.path.expanduser("~/Downloads")
    if os.path.isdir(dl):
        for n in os.listdir(dl):
            if "clown-src" in n and n.endswith(".zip"):
                return ("zip", os.path.join(dl, n))
    return (None, None)


def copy_tree(src_sub, dst, only_ext=None):
    """把来源子目录整体拷到 dst，保留相对结构。仅复制指定扩展名（可选）。"""
    if not os.path.isdir(src_sub):
        print(f"  [skip] 来源缺失: {src_sub}")
        return 0
    os.makedirs(dst, exist_ok=True)
    cnt = 0
    for root, _dirs, files in os.walk(src_sub):
        for f in files:
            if only_ext and not f.endswith(only_ext):
                continue
            s = os.path.join(root, f)
            rel = os.path.relpath(s, src_sub)
            d = os.path.join(dst, rel)
            os.makedirs(os.path.dirname(d), exist_ok=True)
            shutil.copy2(s, d)
            cnt += 1
    return cnt


def main():
    kind, path = find_source()
    if kind is None:
        print("找不到 clown-src 来源。请用 --zip <路径> 或 --src <解压目录>。")
        sys.exit(1)

    tmp = None
    if kind == "zip":
        print(f"解压 zip: {path}")
        tmp = tempfile.mkdtemp(prefix="clown-")
        with zipfile.ZipFile(path) as z:
            z.extractall(tmp)
        src_root = tmp
    else:
        print(f"使用目录: {path}")
        src_root = path

    kb = os.path.join(src_root, "skills", "skill", "知识库")
    rules = os.path.join(src_root, "rules")
    fofa = os.path.join(src_root, "mcp-servers", "fofa_MCP")
    pw = os.path.join(src_root, "bin", "playwright-dual-slot.mjs")

    # 清理并重建目标
    if os.path.isdir(TARGET_SKILL):
        shutil.rmtree(TARGET_SKILL)
    os.makedirs(TARGET_REF, exist_ok=True)

    # 1) SKILL.md
    with open(os.path.join(TARGET_SKILL, "SKILL.md"), "w", encoding="utf-8") as f:
        f.write(SKILL_MD)
    print("  [ok] SKILL.md")

    # 2) 知识库 → references/
    n_kb = copy_tree(kb, TARGET_REF, only_ext=".md")
    print(f"  [ok] 知识库 {n_kb} 篇 -> references/")

    # 3) rules → references/rules/
    n_rules = copy_tree(rules, os.path.join(TARGET_REF, "rules"), only_ext=".md")
    print(f"  [ok] rules {n_rules} 篇 -> references/rules/")

    # 4) FOFA MCP（不含 uv.lock）
    fofa_dst = os.path.join(TARGET_REF, "fofa-mcp")
    fofa_files = ["fofa.py", "README.md", "pyproject.toml", ".env.example", ".env"]
    if os.path.isdir(fofa):
        os.makedirs(fofa_dst, exist_ok=True)
        for fn in fofa_files:
            s = os.path.join(fofa, fn)
            if os.path.isfile(s):
                shutil.copy2(s, os.path.join(fofa_dst, fn))
        with open(os.path.join(TARGET_REF, "fofa-recon.md"), "w", encoding="utf-8") as f:
            f.write(FOFA_RECON_MD)
        print("  [ok] fofa-mcp + fofa-recon.md")
    else:
        print("  [skip] fofa_MCP 缺失")

    # 5) Playwright 双槽脚本（Eta 自有 browser agent，留参考）
    if os.path.isfile(pw):
        shutil.copy2(pw, os.path.join(TARGET_REF, "playwright-dual-slot.mjs"))
        print("  [ok] playwright-dual-slot.mjs (参考)")

    # 6) manifest.json
    with open(MANIFEST, "r", encoding="utf-8") as f:
        manifest = json.load(f)
    ids = {s.get("id") for s in manifest.get("skills", [])}
    if SKILL_ID not in ids:
        manifest["skills"].append({
            "id": SKILL_ID,
            "name": SKILL_ID,
            "description": "SRC 漏洞挖掘 + 白盒 0day 审计全流程技能（黑盒渗透 + 白盒源码审计 + 中文报告，含 49 篇知识库与 12 条行为规则）。",
            "assetPath": f"builtin_skills/{SKILL_ID}",
            "hasScripts": False,
            "hasReferences": True,
            "hasAssets": False,
            "hasEvals": False,
        })
        tmpm = MANIFEST + ".tmp"
        with open(tmpm, "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
        os.replace(tmpm, MANIFEST)
        print(f"  [ok] manifest.json 追加 {SKILL_ID}（共 {len(manifest['skills'])} 个）")
    else:
        print(f"  [skip] manifest 已有 {SKILL_ID}")

    if tmp:
        shutil.rmtree(tmp, ignore_errors=True)

    print("\n下一步（脚本无法改 Kotlin/README，需手动）：")
    print(f'  1) app/src/main/kotlin/io/github/mangi/eta/agent/skill/ReverseSkillCatalog.kt')
    print(f'     REVERSE_SKILL_IDS 集合里加一行 "src-hunt",')
    print(f'  2) README.md：Skills-185 -> Skills-{len(manifest["skills"])}；逆向模式 82 -> 83；新增 src-hunt 章节')
    print(f'  3) git add app/src/main/assets/builtin_skills/src-hunt app/src/main/assets/builtin_skills/manifest.json && git commit')


if __name__ == "__main__":
    main()
