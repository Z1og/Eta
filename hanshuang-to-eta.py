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

# ============================================================
# 配置
# ============================================================

REPO_URL = "https://github.com/aimeoa/hanshuang-codex.git"
REPO_BRANCH = "main"
SKILL_DIRS = ["codex-skills", "codex-skills-v5"]  # 按优先级搜索

# 排除的 Skill（不适配）
EXCLUDED_SKILLS = {
    ".system", "pipeline-renderer", "politics-history",
    "adult-fiction", "phishing-kit", "identity-docs",
    "border-crossing", "finance-movement", "rei-fallback",
    "seagull-mature-content",
}

# ============================================================
# 文本转换规则
# ============================================================

def transform_frontmatter(frontmatter_text: str) -> str:
    # 对 frontmatter 内容也做 .ps1 → .sh 和 winget → apk add 替换
    frontmatter_text = re.sub(r'\.ps1(?=[,/)\s`\n:;]|$)', '.sh', frontmatter_text)
    frontmatter_text = re.sub(r'\.ps1\b', '.sh', frontmatter_text)
    frontmatter_text = re.sub(r'winget', 'apk add', frontmatter_text)
    if "compatibility:" not in frontmatter_text:
        frontmatter_text = frontmatter_text.rstrip() + "\ncompatibility: Requires Eta root shell + Alpine Linux.\n"
    if "metadata:" not in frontmatter_text:
        frontmatter_text = frontmatter_text.rstrip() + "metadata:\n  source: hanshuang-codex-adapted\n  eta-env: alpine-linux\n"
    else:
        frontmatter_text = frontmatter_text.replace(
            "metadata:", "metadata:\n  source: hanshuang-codex-adapted\n  eta-env: alpine-linux"
        )
    return frontmatter_text


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

    raw = skill_md.read_text(encoding='utf-8')

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
    new_fm = transform_frontmatter(raw[3:raw.find('\n---', 3)].strip() if raw.startswith('---') else '')
    new_body = transform_body(body, skill_name)
    new_content = f"---\n{new_fm}---\n\n{new_body}\n"

    # 输出
    target_dir = output_dir / skill_name
    if not dry_run:
        # 先清除旧输出，确保干净
        if target_dir.exists():
            shutil.rmtree(target_dir)
        target_dir.mkdir(parents=True, exist_ok=True)
        (target_dir / "SKILL.md").write_text(new_content, encoding='utf-8')

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
                    sh_content = convert_ps1_to_sh(sf.read_text(encoding='utf-8'))
                    (target_scripts / (sf.stem + '.sh')).write_text(sh_content, encoding='utf-8')
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
# 主流程
# ============================================================

def fetch_repo(target_path: Path):
    if (target_path / ".git").exists():
        print(f"[git] 更新已有仓库: {target_path}")
        subprocess.run(["git", "-C", str(target_path), "pull", "--ff-only"], check=True)
    else:
        print(f"[git] 克隆仓库: {REPO_URL}")
        subprocess.run(
            ["git", "clone", "--depth", "1", "--branch", REPO_BRANCH, REPO_URL, str(target_path)],
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

    # Step 2
    print(f"\n[2/4] 扫描 Skill...")
    skill_dirs = find_skill_dirs(repo_path)
    print(f"  找到 {len(skill_dirs)} 个 Skill")

    # Step 3
    print(f"\n[3/4] 转换...")
    manifest_entries = []

    # 保留原有 Eta 内置 Skill
    if builtin_dir.exists():
        mf = builtin_dir / "manifest.json"
        if mf.exists():
            try:
                existing = json.loads(mf.read_text(encoding='utf-8'))
                for entry in existing.get("skills", []):
                    if entry.get("id") in ("self-improving-agent", "skill-creator", "skill-installer"):
                        manifest_entries.append(entry)
            except Exception:
                pass

    for skill_dir in skill_dirs:
        entry = process_skill(skill_dir, output_dir, dry_run=args.dry_run)
        if entry:
            manifest_entries.append(entry)
            prefix = "[DRY-RUN] " if args.dry_run else ""
            print(f"  {prefix}[OK] {skill_dir.name}")

    # Step 4
    print(f"\n[4/4] 更新 manifest...")
    seen_ids = set()
    unique = []
    for e in manifest_entries:
        if e["id"] not in seen_ids:
            seen_ids.add(e["id"])
            unique.append(e)

    manifest = {"skills": unique}

    if not args.dry_run:
        (output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding='utf-8')

        if args.builtin and builtin_dir.exists():
            (builtin_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding='utf-8')
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
        install_sh.write_text(f"""#!/bin/sh
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
""", encoding='utf-8')

    print(f"\n{'=' * 60}")
    print(f"完成！输出: {output_dir}")
    print(f"Skill: {len(unique)} 个")
    if not args.builtin:
        print(f"  --builtin  可同时部署到 Eta builtin_skills")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    main()
