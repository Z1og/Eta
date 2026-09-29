#!/usr/bin/env python3
"""
hanshuang-to-eta.py — 从 hanshuang-codex 自动适配 Skill 到 Eta

工作流：
1. 拉取 aimeoa/hanshuang-codex 最新代码（git clone / git pull）
2. 遍历 codex-skills/ 目录下的所有 Skill
3. 对每个 SKILL.md 执行自动转换（桌面 → Android/Alpine）
4. 转换 PowerShell 脚本为 Shell 脚本
5. 输出到目标目录（Eta builtin_skills 或独立分发目录）
6. 更新 manifest.json

用法：
  python3 hanshuang-to-eta.py                          # 默认：拉取+转换+部署
  python3 hanshuang-to-eta.py --skip-fetch              # 跳过 git pull，仅转换
  python3 hanshuang-to-eta.py --source /path/to/repo    # 指定本地仓库路径
  python3 hanshuang-to-eta.py --output /path/to/out     # 指定输出目录
  python3 hanshuang-to-eta.py --dry-run                 # 只预览，不写文件
  python3 hanshuang-to-eta.py --builtin                 # 同时部署到 Eta builtin_skills 目录
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


def read_lf(path) -> str:
    """Read text with normalized LF line endings (Windows CRLF checkout safe)."""
    return Path(path).read_text(encoding='utf-8').replace('\r\n', '\n').replace('\r', '\n')


def write_lf(path, text: str) -> None:
    """Write text forcing LF endings regardless of platform."""
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


# ============================================================
# 配置
# ============================================================

REPO_URL = "https://github.com/aimeoa/hanshuang-codex.git"
REPO_BRANCH = "main"
SKILL_DIRS = ["codex-skills", "codex-skills-v5"]  # 按优先级搜索

# 第二来源：newliver666/apk-reverse 深度版（gate 工作流 + 40+ references + 50+ 脚本），
# 覆盖 hanshuang 来源的同名 apk-reverse skill
APK_REVERSE_PRO_URL = "https://github.com/newliver666/apk-reverse.git"
APK_REVERSE_PRO_DIR = "apk-reverse"
APK_REVERSE_PRO_CACHE = "apk-reverse-pro"

# 第三来源：zhaoxuya520/reverse-skill 合集——只融合 Eta 尚不存在的技能（同名保留
# hanshuang 版），跳过非逆向的生产力工具目录；89 个 CTF competition-* 下游技能
# 会令索引膨胀，也不融合（保留 ctf-sandbox 入口即可）
REVERSE_SKILL_COLLECTION_URL = "https://github.com/zhaoxuya520/reverse-skill.git"
REVERSE_SKILL_COLLECTION_CACHE = "reverse-skill"
REVERSE_SKILL_COLLECTION_SUBDIR = "skills"
REVERSE_SKILL_SKIP = {
    "config", "ops", "docs-generator", "diagram-generator", "case-review",
    "field-journal", "browser-automation", "tests", "references", "scripts",
}

# 排除的 Skill（不适配）
EXCLUDED_SKILLS = {
    ".system", "pipeline-renderer", "politics-history",
    "adult-fiction", "phishing-kit", "identity-docs",
    "border-crossing", "finance-movement", "rei-fallback",
    "seagull-mature-content",
}

# ============================================================
# 关键词触发映射（Eta 关键词触发路由）
# 用户消息命中 triggers 中任一关键词 → 自动加载该 Skill 正文
# ============================================================

TRIGGER_MAP = {
    # Trellis 项目规范工作流（Eta 第一方，方案 A 自动探测 + 关键词触发）
    "trellis-init": "初始化trellis,初始化 trellis,trellis初始化,建立项目规范,项目规范初始化",
    "trellis-before-dev": "写代码前,先看规范,按规范开发,读一下规范,写码前",
    "trellis-check": "按规范检查,对照规范检查,规范检查,trellis检查",
    "trellis-update-spec": "更新规范,沉淀规范,更新trellis,规范回写",
    # reverse-skill 合集新增技能（zhaoxuya520/reverse-skill，仅融合 Eta 尚不存在的）
    "ghidra-reverse": "ghidra,ghidra逆向,ghidra反编译",
    "binary-ninja-reverse": "binary ninja,binaryninja,bn逆向",
    "go-rust-reverse": "go逆向,golang逆向,rust逆向,go反编译,rust符号恢复",
    "browser-extension-reverse": "浏览器插件逆向,浏览器扩展逆向,crx逆向,chrome扩展逆向",
    "macos-reverse": "macos逆向,mac逆向,mach-o逆向,ipa静态分析",
    "digital-forensics": "数字取证,电子取证,取证分析",
    "hardware-security": "硬件安全,固件提取,总线调试",
    "code-audit": "代码审计,源码审计,安全审计",
    "ctf-sandbox": "ctf,awd,靶场,比赛题",
    "database-security": "数据库安全",
    "llm-security": "llm安全,提示词注入,prompt注入",
    "malware-analysis": "恶意样本,病毒分析,样本分析",
    "protocol-reverse": "私有协议逆向,协议还原",
    "threat-hunting": "威胁狩猎",
    "threat-intelligence": "威胁情报",
    "supply-chain-security": "供应链安全",
    "wifi-wireless": "wifi安全,无线安全",
    "ot-ics": "工控安全,ot安全",
    "windows-ad": "域渗透,active directory,ad域安全",
    "identity-federation": "身份认证安全,单点登录安全,oauth安全,sso安全",
    "email-security": "邮件安全,钓鱼邮件",
    "cloud-k8s": "k8s安全,容器安全,云安全",
    "thick-client": "客户端渗透,胖客户端分析",
    # 逆向工程
    "ida-reverse": "ida,IDA Pro,反编译,逆向分析,反汇编,ida逆向",
    "radare2": "radare2,r2,rizin,radare",
    "binary-analysis": "二进制分析,binwalk,字符串提取,PE分析,ELF分析,二进制逆向",
    "binary-diff": "bindiff,二进制对比,补丁对比,补丁差分,符号迁移",
    "dotnet-reverse": "dnSpy,.NET逆向,C#反编译,ILSpy,dotnet反编译,IL反编译",
    "js-reverse": "js逆向,JavaScript逆向,混淆还原,前端加密,webpack解密,JS解密,抓包,接口加密",
    "apk-reverse": "apk逆向,反编译apk,apk分析,smali,apktool,jadx,脱壳,重打包,去广告,签名校验,apk破解",
    "mobile-reverse": "移动逆向,ios逆向,ipa逆向,frida脚本,hook框架",
    "dsl-vm-reverse": "虚拟机保护,VM保护,vmp,自定义虚拟机,opcode还原",
    "reverse-engineering": "逆向工程,reverse engineering,静态分析,逆向入门,逆向",
    "reverse-engineering-api": "api逆向,接口逆向,抓包分析,api模拟,harp分析",
    "protocol-reverse-engineering": "协议逆向,协议分析,私有协议,数据包分析",
    "protocol-reversing": "流量分析,protobuf,tlv解析,网络协议解析",
    "unpack-reverse": "脱壳,加壳,unpack,解壳",
    # 渗透测试
    "network-pentest": "渗透测试,内网渗透,端口扫描,漏洞扫描,nmap扫描",
    "api-security": "api安全,接口测试,越权测试,api漏洞,接口安全",
    "web-hack": "web渗透,sql注入,xss,csrf,web漏洞,web攻击",
    "src-hunter": "src挖掘,漏洞挖掘,bug bounty,众测,src",
    "pentest-tools": "渗透工具,渗透框架,msf,metasploit,cs上线,cobalt strike",
    "attack-chain": "攻击链,杀链,渗透链,横向移动,权限维持,后渗透",
    "full-pentest": "完整渗透,全流程渗透,渗透报告,红队评估",
    "full-crack": "破解,去授权,注册机,keygen,补丁破解,license绕过",
    "crack-keygen": "破解,反编译破解,keygen,授权绕过,序列号算法",
    # 内存与注入
    "seagull-memory": "内存读写,内存分析,内存搜索,指针链,内存dump",
    "game-cheat": "游戏辅助,外挂,作弊,修改器,游戏作弊,加速,无敌",
    "game-hacking": "游戏逆向,游戏hack,游戏内存,找基址,偏移定位",
    "seagull-game-hack": "游戏辅助开发,esp,透视,自瞄,游戏注入",
    "seagull-game-security": "游戏安全,反外挂,游戏保护,游戏加固",
    "seagull-anticheat": "反作弊,anti-cheat,反作弊绕过,过检测,游戏检测",
    "dma-attack": "dma攻击,pcie攻击,硬件攻击,dma读取",
    "graphics-api": "dx hook,directx,opengl,vulkan,渲染hook,画面注入",
    "windows-kernel": "内核驱动,windows内核,驱动开发,内核hook,ssdt",
    # 动态分析
    "dynamic-instrumentation": "动态调试,frida,hook注入,动态hook,runtime hook",
    "edr-bypass-re": "edr绕过,杀软绕过,免杀,av绕过,defender绕过",
    "seagull-evasion": "免杀,杀软对抗,检测绕过,流量隐藏,特征规避",
    # 密码与凭证
    "elf-local-auth-patcher": "elf破解,so破解,linux破解,elf patch",
    "linker-fake-load-unwrapper": "linker,so加固,elf脱壳,linker hook",
    # 其他
    "firmware-pentest": "固件分析,路由器漏洞,iot渗透,固件逆向,嵌入式安全",
    "malware-analysis": "恶意代码分析,病毒分析,样本分析,木马分析,勒索分析",
    "seagull-malware": "恶意软件,病毒逆向,样本提取,c2协议,yara规则",
    "seagull-lab": "搭建实验环境,分析环境,靶场搭建,沙箱环境",
    "research-rigor": "安全研究,研究方法,论文分析,漏洞验证",
    "seagull-social-eng": "社工,osint,信息收集,情报收集,子域名收集",
    "seagull-pentest": "渗透,内网,ad渗透,域渗透,active directory",
    "seagull-exploit": "pwn,漏洞利用,堆利用,栈溢出,ctf pwn,exploit编写",
    "pwn-chain": "pwn,堆利用,栈溢出,内核pwn,漏洞利用链",
    "seagull-auto-ops": "海鸥,自动化运维,seagull",
    "seagull-unlimited": "seagull,全域模式,专家模式",
    "seagull-license-security": "授权分析,license分析,卡密系统,验证逻辑",
    "seagull-game-redteam": "游戏红队,游戏攻防,游戏安全测试",
    "mobile-competitive-game-security": "手游安全,游戏加固,手游外挂,竞技游戏安全",
    "mobile-security": "移动安全,android安全,ios安全,app安全",
    "android-reverse": "安卓逆向,android逆向,apk逆向",
    "coldbrew-api-reverse": "api逆向,接口还原",
    "coldbrew-native-reverse": "native逆向,so逆向,原生库逆向",
    "coldbrew-protocol-reverse": "协议还原,协议破解",
    "coldbrew-identity": "身份认证,identity,登录协议",
    "patch-diff-exploit": "补丁分析,1day,nday,cve分析,补丁对比,漏洞复现",
    "game-engine": "游戏引擎,unity逆向,unreal逆向,ue4,ue5,il2cpp",
    "asm-analysis": "汇编分析,汇编代码,asm,汇编阅读",
    "l-license": "license,授权,注册码,激活码",
    # 总控路由：原生关键词触发已接管分流，只留窄触发词避免抢占自动加载名额
    "hanshuang-router": "寒霜总控,寒霜路由",
}

# 通用触发词后缀——skill 名本身作为触发词（LLM 常见表达）
DEFAULT_TRIGGER_SUFFIX = []

# ============================================================
# 文本转换规则
# ============================================================

def transform_frontmatter(frontmatter_text: str, skill_name: str = "") -> str:
    # 对 frontmatter 内容也做 .ps1 → .sh 和 winget → apk add 替换
    frontmatter_text = re.sub(r'\.ps1(?=[,/)\s`\n:;]|$)', '.sh', frontmatter_text)
    frontmatter_text = re.sub(r'\.ps1\b', '.sh', frontmatter_text)
    frontmatter_text = re.sub(r'winget', 'apk add', frontmatter_text)
    if "compatibility:" not in frontmatter_text:
        frontmatter_text = frontmatter_text.rstrip() + "\ncompatibility: Requires Eta root shell + Alpine Linux.\n"
    if "metadata:" not in frontmatter_text:
        frontmatter_text = frontmatter_text.rstrip() + "\nmetadata:\n  source: hanshuang-codex-adapted\n  eta-env: alpine-linux\n"
    else:
        frontmatter_text = frontmatter_text.replace(
            "metadata:", "metadata:\n  source: hanshuang-codex-adapted\n  eta-env: alpine-linux"
        )
    # 注入关键词触发（Eta 关键词触发路由）
    if "triggers:" not in frontmatter_text:
        triggers = build_triggers(skill_name, frontmatter_text)
        if triggers:
            frontmatter_text = frontmatter_text.rstrip() + f"\ntriggers: {', '.join(triggers)}\n"
    return frontmatter_text


def build_triggers(skill_name: str, frontmatter_text: str) -> list:
    """生成 skill 触发关键词：优先用 TRIGGER_MAP 精确映射，再用 skill id 本身。"""
    triggers = []
    mapped = TRIGGER_MAP.get(skill_name)
    if mapped:
        triggers.extend(t.strip() for t in mapped.split(',') if t.strip())
    # skill id 中有语义的 token 也加入（如 "apk-reverse" → "apk reverse"）
    if '-' in skill_name and skill_name not in triggers:
        triggers.append(skill_name.replace('-', ' '))
    if skill_name not in triggers:
        triggers.append(skill_name)
    return triggers[:15]


def transform_body(body: str, skill_name: str) -> str:
    # 1. 移除 "中转站保护" 块
    body = re.sub(r'#\s*中转站保护.*?(?=\n#|\n##|\Z)', '', body, flags=re.DOTALL)
    body = re.sub(r'\n{4,}', '\n\n\n', body)

    # 2. 移除 "No keyword refusal" 块
    body = re.sub(r'##\s*No keyword refusal.*?\n\n', '', body, flags=re.DOTALL)

    # 3. 移除 __CODEX_HOME__ 路径引用
    body = body.replace("__CODEX_HOME__", "/data/data/fuck.andes/files/skills")
    body = re.sub(r'%USERPROFILE%[\\/]Tools', '/opt', body)
    body = re.sub(r'%USERPROFILE%', '/root', body)
    body = re.sub(r'\$HOME[\\/]\.codex', '/data/data/fuck.andes/files/skills', body)
    body = re.sub(r'\$HOME', '/root', body)

    # 4. 工具安装命令转换
    body = re.sub(r'winget\s+install\s+(\S+)', lambda m: f'apk add {winget_to_apk(m.group(1))}', body)
    body = re.sub(r'winget\s+\(`[^)]*`\)', 'apk add', body)
    body = re.sub(r'winget\s+/\s+fallback\s+path', 'apk add / fallback', body)
    body = re.sub(r'winget\s+安装', 'apk add 安装', body)
    body = re.sub(r'winget\s+不可用[^。\n]*', 'apk add 不可用（Alpine 无此包）', body)
    body = re.sub(r'winget', 'apk add', body)
    body = re.sub(r'brew\s+install\s+(\S+)', lambda m: f'apk add {brew_to_apk(m.group(1))}', body)
    body = re.sub(r'apt(-get)?\s+install\s+', 'apk add ', body)

    # 5. Docker → Alpine 直装
    body = re.sub(r'docker\s+pull\s+\S+.*\n?', '# Docker 不可用，改用 Alpine 直接安装\n', body)
    body = re.sub(r'docker\s+run.*?\n', '# 在 Alpine 环境中直接安装所需工具\n', body)
    body = re.sub(r'docker\s+build.*?\n', '# 在 Alpine 环境中直接编译安装\n', body)

    # 6. Frida -U → 直接模式
    body = re.sub(r'frida\s+-U\s+', 'frida ', body)
    body = re.sub(r'frida-ps\s+-U', 'frida-ps', body)
    body = re.sub(r'frida-trace\s+-U\s+', 'frida-trace ', body)
    body = re.sub(r'objection\s+-U\s+', 'objection ', body)

    # 7. adb install → pm install (本机)
    body = re.sub(r'adb\s+install\s+-r\s+', 'pm install -r ', body)
    body = re.sub(r'adb\s+install\s+', 'pm install ', body)

    # 8. PowerShell 脚本 → Shell 脚本
    body = re.sub(r'\.ps1(?=[,/)\s`\n:;]|$)', '.sh', body)
    body = re.sub(r'\.ps1\b', '.sh', body)
    body = re.sub(r'ad-hoc\s+(P|p)ower[Ss]hell', 'ad-hoc shell', body)
    body = re.sub(r'PowerShell\b', 'Shell', body)
    body = re.sub(r'powershell\b', 'sh', body, flags=re.IGNORECASE)
    body = re.sub(r'pwsh\b', 'sh', body)

    # 9. 路由上下文转换
    body = re.sub(r'\*\*上游入口\*\*:\s*`skills/SKILL\.md`（总控）、`routing\.md`', '**上游入口**: `hanshuang-router`', body)
    body = re.sub(r'→ `(\w+)/`', r'→ `\1`', body)
    body = re.sub(r'→ `(\w+\.md)`', r'→ 参考 \1', body)

    # 10. 添加 Eta 环境说明
    if "Eta" not in body and "alpine" not in body.lower():
        eta_note = """
## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写
"""
        first_section = body.find('\n## ')
        if first_section > 0:
            body = body[:first_section] + eta_note + body[first_section:]
        else:
            body = eta_note + body

    return body


# ============================================================
# 工具名映射
# ============================================================

WINGET_MAP = {"Insecure.Nmap": "nmap", "Nmap.Nmap": "nmap"}

BREW_MAP = {
    "gdb": "gdb", "radare2": "radare2", "binutils": "binutils",
    "upx": "upx", "nmap": "nmap", "tshark": "tshark",
    "nikto": "nikto", "john-jumbo": "john", "hashcat": "hashcat", "hydra": "hydra",
}

def winget_to_apk(pkg): return WINGET_MAP.get(pkg, pkg.split(".")[-1].lower() if "." in pkg else pkg.lower())
def brew_to_apk(pkg):
    mapped = BREW_MAP.get(pkg)
    return mapped if mapped else pkg


# ============================================================
# PowerShell → Shell 转换器
# ============================================================

def convert_ps1_to_sh(ps1_content: str) -> str:
    lines = ps1_content.split('\n')
    sh_lines = ['#!/bin/sh', '# Auto-converted from PowerShell to Shell for Eta Alpine Linux', '']
    for line in lines:
        stripped = line.strip()
        if any(stripped.startswith(s) for s in [
            '[CmdletBinding()]', 'param(', 'param (', '$ErrorActionPreference',
            '$PSVersionTable', 'trap', 'try {', 'catch', 'finally',
        ]):
            continue
        if stripped.startswith('Write-Host'):
            msg = re.sub(r'Write-Host\s+"([^"]*)"', r'echo "\1"', stripped)
            msg = re.sub(r'Write-Host\s+(\S+)', r'echo \1', msg)
            msg = re.sub(r'-ForegroundColor\s+\w+', '', msg)
            sh_lines.append(msg)
            continue
        if stripped.startswith('Write-Warning'):
            msg = re.sub(r'Write-Warning\s+"([^"]*)"', r'echo "[WARN] \1"', stripped)
            sh_lines.append(msg)
            continue
        if stripped.startswith(('Write-Log', 'Write-Verbose', 'Write-Debug')):
            continue
        if stripped.startswith(('return', 'exit')):
            sh_lines.append(stripped)
            continue
        # Variable uppercase
        line = re.sub(r'\$([A-Z][a-zA-Z0-9]*)', lambda m: '$' + m.group(1).upper(), line)
        line = line.replace('Test-Path', 'test -e')
        line = re.sub(r'Copy-Item\s+(?:-\w+\s+)*"([^"]*)"\s+-Destination\s+"([^"]*)"', r'cp "\1" "\2"', line)
        line = re.sub(r'Remove-Item\s+-LiteralPath\s+"([^"]*)"\s+-Recurse\s+-Force', r'rm -rf "\1"', line)
        line = re.sub(r'New-Item\s+-ItemType\s+Directory\s+-Path\s+"([^"]*)"\s+-Force', r'mkdir -p "\1"', line)
        line = re.sub(r'Join-Path\s+(\$\w+)\s+([^\s]+)', r'\1/\2', line)
        line = line.replace('Get-Content', 'cat')
        line = line.replace('| Out-Null', '>/dev/null 2>&1')
        line = line.replace('-ErrorAction Stop', '')
        sh_lines.append(line)
    return '\n'.join(sh_lines)


# ============================================================
# 核心：处理单个 Skill
# ============================================================

def process_skill(skill_dir: Path, output_dir: Path, dry_run: bool = False) -> dict | None:
    skill_name = skill_dir.name
    skill_md = skill_dir / "SKILL.md"
    if not skill_md.exists():
        return None

    raw = read_lf(skill_md)

    # 解析 frontmatter
    fm = {}
    body = raw
    if raw.startswith('---'):
        end = raw.find('\n---', 3)
        if end > 0:
            fm_text = raw[3:end].strip()
            body = raw[end + 4:].strip()
            for line in fm_text.split('\n'):
                m = re.match(r'^(\w+):\s*(.*)', line)
                if m:
                    fm[m.group(1)] = m.group(2).strip().strip('"').strip("'")

    # 转换
    new_fm = transform_frontmatter(
        raw[3:raw.find('\n---', 3)].strip() if raw.startswith('---') else '',
        skill_name=skill_name,
    )
    new_body = transform_body(body, skill_name)
    new_content = f"---\n{new_fm}---\n\n{new_body}\n"

    # 输出
    target_dir = output_dir / skill_name
    if not dry_run:
        # 先清除旧输出，确保干净
        if target_dir.exists():
            shutil.rmtree(target_dir)
        target_dir.mkdir(parents=True, exist_ok=True)
        write_lf(target_dir / "SKILL.md", new_content)

    # 复制 references/
    has_refs = False
    refs_dir = skill_dir / "references"
    if refs_dir.exists() and refs_dir.is_dir():
        has_refs = True
        if not dry_run:
            shutil.copytree(refs_dir, target_dir / "references")

    # 转换 scripts/
    has_scripts = False
    scripts_dir = skill_dir / "scripts"
    if scripts_dir.exists() and scripts_dir.is_dir():
        has_scripts = True
        if not dry_run:
            target_scripts = target_dir / "scripts"
            target_scripts.mkdir(parents=True, exist_ok=True)
            for sf in scripts_dir.iterdir():
                if sf.suffix == '.ps1':
                    sh_content = convert_ps1_to_sh(read_lf(sf))
                    write_lf(target_scripts / (sf.stem + '.sh'), sh_content)
                elif sf.is_file():
                    shutil.copy2(sf, target_scripts / sf.name)

    # 复制 assets/ 和 evals/
    has_assets = (skill_dir / "assets").is_dir()
    has_evals = (skill_dir / "evals").is_dir()
    if not dry_run:
        if has_assets:
            shutil.copytree(skill_dir / "assets", target_dir / "assets")
        if has_evals:
            shutil.copytree(skill_dir / "evals", target_dir / "evals")

    skill_id = fm.get("name", skill_name).lower().replace('_', '-').replace(' ', '-')
    skill_id = re.sub(r'[^a-z0-9-]+', '-', skill_id).strip('-') or skill_name.lower()

    return {
        "id": skill_id,
        "name": fm.get("name", skill_name),
        "description": fm.get("description", f"Adapted from hanshuang-codex: {skill_name}")[:200],
        "assetPath": f"builtin_skills/{skill_name}",
        "hasScripts": has_scripts,
        "hasReferences": has_refs,
        "hasAssets": has_assets,
        "hasEvals": has_evals,
    }


# ============================================================
# 第二来源：apk-reverse 深度版融合
# ============================================================

ETA_APK_REVERSE_NOTE = """
## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 脚本统一用 Linux 工具环境（environment=linux）执行：`python3 scripts/xxx.py`
- Python 依赖：`apk add python3` + `pip install`；droidasc/ddc 按需 `pip install`
- baksmali/smali、apksigner、adb 等按需安装；`APKREV_TOOLS` 指向额外工具目录
- 目标 APK 与产物放在 /workspace 下的共享目录中操作
"""


def promote_apk_reverse_pro(output_dir: Path, pro_cache: Path, dry_run: bool = False) -> bool:
    """用 newliver666/apk-reverse 深度版覆盖同名 apk-reverse skill。

    深度版本身已是标准 Agent Skills 格式，只需：整目录替换 + 注入触发词 + 追加 Eta 环境说明。
    源缓存缺失时静默保留 hanshuang 版本，不中断适配流程。
    """
    src = pro_cache / "skills" / APK_REVERSE_PRO_DIR
    if not (src / "SKILL.md").exists():
        print("  [警告] apk-reverse 深度版源缺失，保留 hanshuang 版本")
        return False
    dst = output_dir / APK_REVERSE_PRO_DIR
    if dry_run:
        print("  [DRY-RUN] apk-reverse 将被深度版覆盖")
        return True
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)

    skill_file = dst / "SKILL.md"
    text = read_lf(skill_file)
    mapped = TRIGGER_MAP.get(APK_REVERSE_PRO_DIR, "")
    if mapped and "triggers:" not in text and text.startswith('---'):
        end = text.find('\n---', 3)
        if end > 0:
            triggers = ', '.join(t.strip() for t in mapped.split(',') if t.strip())
            fm_text = text[3:end].rstrip() + f"\ntriggers: {triggers}"
            text = f"---\n{fm_text}\n---{text[end + 4:]}"
    if not text.rstrip().endswith(ETA_APK_REVERSE_NOTE.strip()):
        text = text.rstrip() + '\n' + ETA_APK_REVERSE_NOTE
    write_lf(skill_file, text)

    # 清理维护工具（不属于 skill 本体）
    for junk in ("evidence",):
        junk_dir = dst / junk
        if junk_dir.exists():
            shutil.rmtree(junk_dir)
    print("  [融合] apk-reverse 已升级为深度版（newliver666/apk-reverse）")
    return True


def promote_reverse_skill_collection(output_dir: Path, cache_root: Path, existing_ids: set, dry_run: bool = False) -> list:
    """融合 zhaoxuya520/reverse-skill 合集中 Eta 尚不存在的技能。

    策略：同名 id 保留 hanshuang 版（已适配、用户熟悉）；跳过非逆向工具目录与
    89 个 CTF competition-* 下游技能（索引膨胀）。跨目录引用改写为技能索引
    引用，共享 field-journal/precedent 与 tool-index 落进各技能的 references/。
    """
    src_root = cache_root / REVERSE_SKILL_COLLECTION_SUBDIR
    if not src_root.is_dir():
        print("  [警告] reverse-skill 合集源缺失，跳过")
        return []
    # 共享资源（供内联拷贝）
    shared_files = {}
    fj = src_root / "field-journal"
    if fj.is_dir():
        for f in sorted(fj.glob("*.md")):
            shared_files[f"field-journal/{f.name}"] = read_lf(f)
    ti = src_root / "tool-index.md"
    if ti.exists():
        shared_files["tool-index.md"] = read_lf(ti)

    added = []
    for child in sorted(src_root.iterdir()):
        if not child.is_dir() or child.name.startswith('.') or child.name in REVERSE_SKILL_SKIP:
            continue
        skill_md = child / "SKILL.md"
        if not skill_md.exists():
            continue
        skill_id = child.name
        if skill_id in existing_ids or (output_dir / skill_id).exists():
            continue
        dst = output_dir / skill_id
        if not dry_run:
            shutil.copytree(child, dst)
            text = read_lf(dst / "SKILL.md")
            # 跨技能引用 → 索引引用（Eta 技能索引含这些 id，模型可 skills_read）
            text = re.sub(r'\.\./([a-z0-9-]+)/(?!field-journal)', r'\1 ', text)
            # 共享文件 → 技能内 references/ 并内联拷贝
            for key, content in shared_files.items():
                name = key.split('/')[-1]
                if key in text or key.split('/')[-1] in text:
                    (dst / "references").mkdir(parents=True, exist_ok=True)
                    write_lf(dst / "references" / name, content)
                    text = text.replace(f"../{key}", f"references/{name}")
            text = re.sub(r'\.\./([a-zA-Z0-9_.-]+\.md)', r'references/\1', text)
            mapped = TRIGGER_MAP.get(skill_id, "")
            if mapped and "triggers:" not in text and text.startswith('---'):
                end = text.find('\n---', 3)
                if end > 0:
                    triggers = ', '.join(t.strip() for t in mapped.split(',') if t.strip())
                    fm_text = text[3:end].rstrip() + f"\ntriggers: {triggers}"
                    text = f"---\n{fm_text}\n---{text[end + 4:]}"
            write_lf(dst / "SKILL.md", text)
        m = re.search(r'^description:\s*(.+)$', read_lf(skill_md)[:2000], re.MULTILINE)
        desc = (m.group(1).strip().strip('"') if m else f"reverse-skill collection: {skill_id}")[:200]
        added.append({
            "id": skill_id,
            "name": skill_id,
            "description": desc,
            "assetPath": f"builtin_skills/{skill_id}",
            "hasScripts": (child / "scripts").is_dir(),
            "hasReferences": (child / "references").is_dir() or bool(shared_files),
            "hasAssets": (child / "assets").is_dir(),
            "hasEvals": (child / "evals").is_dir(),
        })
        print(f"  [融合] reverse-skill 新增: {skill_id}")
    return added


def refresh_manifest_flags(manifest: dict, output_dir: Path) -> None:
    """融合后按真实目录修正 apk-reverse 的能力标记与描述（以深度版 frontmatter 为准）。"""
    for entry in manifest.get("skills", []):
        if entry.get("id") != APK_REVERSE_PRO_DIR:
            continue
        d = output_dir / APK_REVERSE_PRO_DIR
        entry["hasScripts"] = (d / "scripts").is_dir()
        entry["hasReferences"] = (d / "references").is_dir()
        entry["hasAssets"] = (d / "assets").is_dir()
        entry["hasEvals"] = (d / "evals").is_dir()
        skill_md = d / "SKILL.md"
        if skill_md.exists():
            m = re.search(r'^description:\s*(.+)$', read_lf(skill_md)[:2000], re.MULTILINE)
            if m and m.group(1).strip():
                entry["description"] = m.group(1).strip().strip('"')[:200]


# ============================================================
# 主流程
# ============================================================

def fetch_repo(target_path: Path, url: str = REPO_URL, branch: str = REPO_BRANCH):
    if (target_path / ".git").exists():
        print(f"[git] 更新已有仓库: {target_path}")
        subprocess.run(["git", "-C", str(target_path), "pull", "--ff-only"], check=True)
    else:
        print(f"[git] 克隆仓库: {url}")
        subprocess.run(
            ["git", "clone", "--depth", "1", "--branch", branch, url, str(target_path)],
            check=True
        )


def find_skill_dirs(repo_path: Path) -> list[Path]:
    skill_dirs = []
    for skill_dir_name in SKILL_DIRS:
        base = repo_path / skill_dir_name
        if not base.exists():
            continue
        for child in sorted(base.iterdir()):
            if not child.is_dir() or child.name.startswith('.') or child.name in EXCLUDED_SKILLS:
                if child.name in EXCLUDED_SKILLS:
                    print(f"  [跳过] {child.name}（排除列表）")
                continue
            if (child / "SKILL.md").exists():
                skill_dirs.append(child)
    # 去重
    seen = set()
    return [d for d in skill_dirs if d.name not in seen and not seen.add(d.name)]


def main():
    parser = argparse.ArgumentParser(description="从 hanshuang-codex 自动适配 Skill 到 Eta")
    parser.add_argument("--skip-fetch", action="store_true")
    parser.add_argument("--source", type=str)
    parser.add_argument("--output", type=str)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--builtin", action="store_true", help="同时部署到 Eta builtin_skills 目录")
    args = parser.parse_args()

    script_dir = Path(__file__).parent.resolve()
    project_root = script_dir

    repo_path = Path(args.source) if args.source else project_root / ".cache" / "hanshuang-codex"
    output_dir = Path(args.output) if args.output else project_root / "hanshuang-eta-skills"
    builtin_dir = project_root / "app" / "src" / "main" / "assets" / "builtin_skills"

    print("=" * 60)
    print("寒霜 → Eta 自动适配器")
    print("=" * 60)

    # Step 1
    if not args.skip_fetch and not args.source:
        print(f"\n[1/4] 拉取 hanshuang-codex...")
        try:
            fetch_repo(repo_path)
        except subprocess.CalledProcessError as e:
            print(f"[错误] Git 失败: {e}")
            sys.exit(1)
    else:
        print(f"\n[1/4] 使用本地: {repo_path}")

    pro_cache = project_root / ".cache" / APK_REVERSE_PRO_CACHE
    if not args.skip_fetch and not args.source:
        print(f"\n[1.5/4] 拉取 apk-reverse 深度版...")
        try:
            fetch_repo(pro_cache, APK_REVERSE_PRO_URL, branch="main")
        except subprocess.CalledProcessError as e:
            print(f"[警告] apk-reverse 深度版拉取失败，将保留 hanshuang 版本: {e}")
        print(f"\n[1.6/4] 拉取 reverse-skill 合集...")
        try:
            fetch_repo(project_root / ".cache" / REVERSE_SKILL_COLLECTION_CACHE, REVERSE_SKILL_COLLECTION_URL, branch="main")
        except subprocess.CalledProcessError as e:
            print(f"[警告] reverse-skill 合集拉取失败，将跳过: {e}")

    # Step 2
    print(f"\n[2/4] 扫描 Skill...")
    skill_dirs = find_skill_dirs(repo_path)
    print(f"  找到 {len(skill_dirs)} 个 Skill")

    # Step 3
    print(f"\n[3/4] 转换...")
    manifest_entries = []

    # 保留非 hanshuang 来源的内置 Skill（Eta 上游原生 skill，动态保留而非硬编码 ID，
    # 这样上游新增内置 skill 时 manifest 不会丢条目）
    hanshuang_dir_names = {d.name for d in skill_dirs}
    if builtin_dir.exists():
        mf = builtin_dir / "manifest.json"
        if mf.exists():
            try:
                existing = json.loads(read_lf(mf))
                for entry in existing.get("skills", []):
                    entry_id = entry.get("id", "")
                    asset_path = entry.get("assetPath", "")
                    # 跳过 hanshuang 适配的条目（稍后由适配器重新生成）
                    dir_name = asset_path.rsplit("/", 1)[-1] if asset_path else entry_id
                    if dir_name in hanshuang_dir_names or entry_id in hanshuang_dir_names:
                        continue
                    # 目录仍存在才保留（防止上游删除后残留）
                    if (builtin_dir / dir_name / "SKILL.md").exists():
                        manifest_entries.append(entry)
            except Exception as e:
                print(f"  [警告] 解析现有 manifest 失败: {e}")

        # 兜底：扫描 builtin_dir 中有 SKILL.md 但 manifest 未覆盖的目录
        # （上游新增 skill 但还没写进 manifest 的情况）
        covered = {e.get("id") for e in manifest_entries}
        try:
            for child in sorted(builtin_dir.iterdir()):
                if not child.is_dir() or child.name.startswith('.') or child.name in hanshuang_dir_names:
                    continue
                if not (child / "SKILL.md").exists():
                    continue
                if child.name in covered:
                    continue
                print(f"  [补录] 上游内置 Skill 缺 manifest 条目: {child.name}")
                # 孤儿 skill 若在 TRIGGER_MAP 中且缺 triggers，注入触发词
                skill_file = child / "SKILL.md"
                triggers = []
                mapped = TRIGGER_MAP.get(child.name)
                if mapped:
                    triggers = [t.strip() for t in mapped.split(',') if t.strip()]
                    try:
                        text = read_lf(skill_file)
                        if "triggers:" not in text and text.startswith('---'):
                            end = text.find('\n---', 3)
                            if end > 0:
                                fm_text = text[3:end].rstrip()
                                fm_text += f"\ntriggers: {', '.join(triggers[:15])}"
                                write_lf(
                                    skill_file,
                                    f"---\n{fm_text}\n---{text[end + 4:]}",
                                )
                    except Exception as e:
                        print(f"  [警告] 注入 triggers 失败: {e}")
                manifest_entries.append({
                    "id": child.name,
                    "name": child.name,
                    "description": f"Eta upstream builtin skill: {child.name}",
                    "assetPath": f"builtin_skills/{child.name}",
                    "hasScripts": (child / "scripts").is_dir(),
                    "hasReferences": (child / "references").is_dir(),
                    "hasAssets": (child / "assets").is_dir(),
                    "hasEvals": (child / "evals").is_dir(),
                })
        except Exception as e:
            print(f"  [警告] 扫描 builtin_dir 失败: {e}")

    for skill_dir in skill_dirs:
        entry = process_skill(skill_dir, output_dir, dry_run=args.dry_run)
        if entry:
            manifest_entries.append(entry)
            prefix = "[DRY-RUN] " if args.dry_run else ""
            print(f"  {prefix}[OK] {skill_dir.name}")

    # 第二来源融合：apk-reverse 深度版覆盖同名 skill
    print(f"\n[3.5/4] 融合 apk-reverse 深度版...")
    promoted = promote_apk_reverse_pro(output_dir, pro_cache, dry_run=args.dry_run)

    # 第三来源融合：reverse-skill 合集（仅新增 id）
    print(f"\n[3.7/4] 融合 reverse-skill 合集...")
    collection_added = promote_reverse_skill_collection(
        output_dir,
        project_root / ".cache" / REVERSE_SKILL_COLLECTION_CACHE,
        {e["id"] for e in manifest_entries},
        dry_run=args.dry_run,
    )
    manifest_entries.extend(collection_added)

    # Step 4
    print(f"\n[4/4] 更新 manifest...")
    seen_ids = set()
    unique = []
    for e in manifest_entries:
        if e["id"] not in seen_ids:
            seen_ids.add(e["id"])
            unique.append(e)

    manifest = {"skills": unique}
    if promoted:
        refresh_manifest_flags(manifest, output_dir)

    if not args.dry_run:
        write_lf(output_dir / "manifest.json", json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")

        # 同步孤儿内置 skill 到镜像目录，保证 manifest 与目录一致
        # （补录的上游 skill 只存在于 builtin_dir，镜像分发目录也需要它）
        # 仅处理 id 与目录名一致的条目——hanshuang skill 的目录名由 process_skill
        # 写入镜像，若按 entry.id 复制会给"frontmatter 名 ≠ 目录名"的 skill
        # 生成改名副本，经 deploy 回写后在 builtin 产生重复目录
        for entry in unique:
            src = builtin_dir / entry["assetPath"].rsplit("/", 1)[-1]
            dst = output_dir / src.name
            if (src.name == entry["id"] and src.exists()
                    and (src / "SKILL.md").exists() and not dst.exists()
                    and src.name not in hanshuang_dir_names):
                shutil.copytree(src, dst)
                print(f"  [镜像] 补充孤儿 skill: {src.name}")

        if args.builtin and builtin_dir.exists():
            write_lf(builtin_dir / "manifest.json", json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
            for sd in output_dir.iterdir():
                if sd.is_dir() and (sd / "SKILL.md").exists():
                    target = builtin_dir / sd.name
                    if target.exists():
                        shutil.rmtree(target)
                    shutil.copytree(sd, target)
            print(f"  已部署到 builtin_skills")

        print(f"  manifest: {len(unique)} 个 Skill")

    # 也复制安装脚本
    install_sh = output_dir / "install-eta-skills.sh"
    if not install_sh.exists() and not args.dry_run:
        write_lf(install_sh, f"""#!/bin/sh
# install-eta-skills.sh — 将适配后的寒霜技能包安装到 Eta
# 用法: sh install-eta-skills.sh [eta-skills-dir]
set -e
ETA_SKILLS_DIR="${{1:-/data/data/fuck.andes/files/skills}}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
echo "=== 寒霜技能包 Eta 适配版安装 ==="
echo "源: $SCRIPT_DIR"
echo "目标: $ETA_SKILLS_DIR"
INSTALLED=0; SKIPPED=0
for d in "$SCRIPT_DIR"/*/; do
    name=$(basename "$d")
    [ ! -f "$d/SKILL.md" ] && continue
    target="$ETA_SKILLS_DIR/$name"
    if [ -d "$target" ] && [ -f "$target/SKILL.md" ]; then
        echo "[跳过] $name — 已存在"
        SKIPPED=$((SKIPPED+1)); continue
    fi
    mkdir -p "$target"
    cp -r "$d"* "$target/" && echo "[安装] $name" && INSTALLED=$((INSTALLED+1))
done
echo "完成: 安装=$INSTALLED 跳过=$SKIPPED"
""")

    print(f"\n{'=' * 60}")
    print(f"完成！输出: {output_dir}")
    print(f"Skill: {len(unique)} 个")
    if not args.builtin:
        print(f"  --builtin  可同时部署到 Eta builtin_skills")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    main()
