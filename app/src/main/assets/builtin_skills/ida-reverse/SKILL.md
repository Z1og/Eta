---
name: ida-reverse
description: |
  IDA Pro 逆向分析辅助（Eta 适配版）。当目标需要 IDA 反编译/Hex-Rays 时使用。
  在 Eta 上通过 radare2 + r2ghidra 作为替代，或通过远程 IDA 实例操作。
  触发：IDA、IDA Pro、Hex-Rays、反编译、.idb、.i64、decompiler。
compatibility: Requires Eta root shell. IDA Pro via remote or radare2+r2ghidra as fallback.
metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---

# IDA 逆向分析（Eta 适配版）

IDA Pro 通常需要 GUI 桌面环境。在 Eta 上有两种使用方式：
1. **radare2 + r2ghidra**：本地 Alpine 环境中的免费替代
2. **远程 IDA**：通过网络连接 PC 上的 IDA Pro

## 本地方案：radare2 + r2ghidra

```bash
# 安装
apk add radare2
r2pm -ci r2ghidra  # Ghidra 反编译插件

# 分析二进制
r2 -A /path/to/binary
afl                  # 列出函数
pdf @ sym.main       # 反汇编 main
pdg @ sym.main       # Ghidra 反编译（r2ghidra）
```

### radare2 常用命令

```bash
# 信息
ii          # 导入
iE          # 导出
is          # 符号
iz          # 字符串

# 分析
aaa         # 深度分析
af @ sym.main
pdf @ sym.main

# 交叉引用
axt @ addr  # 谁引用了这个地址
axf @ addr  # 这个地址引用了谁

# 搜索
/w flag     # 搜索字符串
/e search.from=0x400000
/e search.to=0x401000
/           # 搜索字节模式

# 补丁
wa nop     # 写入 NOP
wx 90909090 @ addr
```

## 远程 IDA Pro

如果 PC 上有 IDA Pro，可通过以下方式远程操作：

### IDA Python 脚本（PC 端）

```python
# 在 IDA 中运行脚本，结果通过文件共享或网络传回 Eta
import idautils
import idc

# 列出函数
for func_ea in idautils.Functions():
    print(f"{hex(func_ea)}: {idc.get_func_name(func_ea)}")

# 反编译
import ida_hexrays
cfunc = ida_hexrays.decompile(func_ea)
print(str(cfunc))
```

### Eta 通过 SSH/SMB 访问 PC

```bash
# SSH 到 PC
ssh user@pc-ip

# 或通过 Samba 共享
mount -t cifs //pc-ip/share /mnt/pc -o user=xxx

# 将分析目标传到 PC，运行 IDA headless
ssh user@pc 'ida64 -A -S"script.py" -oresult.txt target.bin'
```

## 常见分析任务

### ARM64 .so 分析

```bash
r2 -A lib/arm64-v8a/libnative.so

# JNI 函数
afl~Java

# 导出函数
iE

# 反编译关键函数
pdg @ sym.Java_com_example_NativeLib_sign
```

### 符号恢复

```bash
# 查找 RTTI / symbol table
is~typeinfo
is~vtable

# 字符串引用
iz
axt @ str.secret_key
```

## 路由上下文

**上游入口**: `hanshuang-router`、`apk-reverse`、`reverse-engineering`
**下游出口**:
- 需要 Frida 验证 → `dynamic-instrumentation`
- 需要协议分析 → `protocol-reversing`
