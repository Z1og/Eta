#!/bin/sh
# install-eta-skills.sh — 将适配后的寒霜技能包安装到 Eta
# 用法: sh install-eta-skills.sh [eta-skills-dir]
set -e
ETA_SKILLS_DIR="${1:-/data/data/fuck.andes/files/skills}"
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
