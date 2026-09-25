---
name: binary-analysis
description: |
  静态二进制逆向工程（Eta 适配版）：PE/ELF 结构分析、模式扫描、反汇编、补丁生成。
  触发：二进制分析、PE分析、ELF分析、节表、导入表、导出表、模式扫描、反汇编。
compatibility: Requires Eta root shell + Alpine Linux with radare2/binutils.
metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---

# 静态二进制分析（Eta 适配版）

## 工具安装

```bash
# Alpine
apk add binutils file radare2
pip install capstone lief pyelftools pefile
```

## 文件类型识别

```bash
file target_binary
readelf -h target_binary    # ELF 头
xxd target_binary | head -4  # 魔数
```

## ELF 分析

```bash
# 节表
readelf -S target_binary

# 符号表
readelf -s target_binary
nm target_binary

# 导入/导出
readelf -r target_binary     # 重定位
objdump -T target_binary     # 动态符号
objdump -d target_binary     # 反汇编

# 依赖库
readelf -d target_binary | grep NEEDED
ldd target_binary
```

## PE 分析

```bash
# Python pefile
python3 -c "
import pefile
pe = pefile.PE('target.exe')
print('Sections:')
for s in pe.sections:
    print(f'  {s.Name.decode()}: VirtualSize={s.Misc_VirtualSize}')
print('Imports:')
for entry in pe.DIRECTORY_ENTRY_IMPORT:
    print(f'  {entry.dll.decode()}')
    for imp in entry.imports:
        print(f'    {imp.name}')
"
```

## radare2 深度分析

```bash
r2 -A target_binary

# 结构概览
ii          # 导入
iE          # 导出
is          # 符号
iz          # 字符串
iS          # 段

# 函数分析
afl          # 列出所有函数
af @ sym.main
pdf @ sym.main

# 交叉引用
axt @ addr
axf @ addr

# 搜索
/w keyword
/x 41424344  # 搜索字节模式

# 反编译（r2ghidra）
pdg @ sym.main
```

## 补丁生成

```bash
# radare2 补丁
r2 -w target_binary
/a search_pattern   # 搜索
s addr             # 跳转
wa nop             # 写汇编
wx 90909090        # 写字节
r2 -c "s 0x401000; wx 90909090" -w target_binary
```

## 路由上下文

**上游入口**: `hanshuang-router`
**下游出口**:
- 需要 Ghidra/IDA 反编译 → `ida-reverse`
- 需要动态分析 → `dynamic-instrumentation`
- 需要漏洞利用 → `pentest-tools`
