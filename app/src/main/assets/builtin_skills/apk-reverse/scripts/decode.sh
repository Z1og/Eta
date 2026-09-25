#!/bin/sh
# decode.sh — Eta Alpine 环境下的 APK 一键解包脚本
# 用法: sh decode.sh /path/to/app.apk [--skip-jadx] [--clean]

set -e

APK_PATH=""
SKIP_JADX=0
CLEAN=0

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-jadx) SKIP_JADX=1; shift ;;
        --clean) CLEAN=1; shift ;;
        *) APK_PATH="$1"; shift ;;
    esac
done

if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
    echo "[ERROR] 用法: sh decode.sh /path/to/app.apk [--skip-jadx] [--clean]"
    exit 1
fi

BASENAME=$(basename "$APK_PATH" .apk)
OUTDIR="/tmp/apk-analysis-${BASENAME}"
JADX_OUT="${OUTDIR}/jadx_out"
APKTOOL_OUT="${OUTDIR}/apktool_out"

if [ "$CLEAN" = 1 ] && [ -d "$OUTDIR" ]; then
    echo "[INFO] 清理旧输出: $OUTDIR"
    rm -rf "$OUTDIR"
fi

mkdir -p "$OUTDIR"

echo "=== APK 解包: $APK_PATH ==="
echo "输出目录: $OUTDIR"

# apktool 解包
echo "[1/2] apktool 解包..."
apktool d "$APK_PATH" -o "$APKTOOL_OUT" -f 2>&1 | tail -5

# jadx 反编译
if [ "$SKIP_JADX" = 0 ]; then
    echo "[2/2] jadx Java 反编译..."
    jadx -d "$JADX_OUT" "$APK_PATH" --no-res 2>&1 | tail -5 || echo "[WARN] jadx 部分反编译失败，但产物可能可用"
else
    echo "[2/2] 跳过 jadx (--skip-jadx)"
fi

# 生成摘要
echo ""
echo "=== 摘要 ==="

# 包名
if [ -f "$APKTOOL_OUT/AndroidManifest.xml" ]; then
    PKG=$(grep -o 'package="[^"]*"' "$APKTOOL_OUT/AndroidManifest.xml" | head -1 | cut -d'"' -f2)
    echo "package: $PKG"
fi

# Java 文件数
if [ -d "$JADX_OUT" ]; then
    JAVA_COUNT=$(find "$JADX_OUT" -name "*.java" | wc -l)
    echo "java_files: $JAVA_COUNT"
fi

# smali 目录数
SMALI_COUNT=$(find "$APKTOOL_OUT" -maxdepth 1 -type d -name "smali*" | wc -l)
echo "smali_dirs: $SMALI_COUNT"

# .so 文件
SO_FILES=$(find "$APKTOOL_OUT/lib" -name "*.so" 2>/dev/null | wc -l)
echo "so_files: $SO_FILES"
if [ "$SO_FILES" -gt 0 ]; then
    find "$APKTOOL_OUT/lib" -name "*.so" 2>/dev/null | sed 's|.*/lib/||' | head -10
fi

echo ""
echo "[完成] 输出目录: $OUTDIR"
