---
name: il2cpp-dump
description: |
  Unity IL2CPP 与 Unreal Engine 游戏的内存 dump 与还原：Il2CppDumper/Zygisk-Il2CppDumper
  提取 global-metadata 与方法/类结构、libil2cpp.so 符号恢复、UE 的 GObjects/GNames 定位、
  dump 结果导入 IDA/Ghidra。当目标是 Unity IL2CPP 或 UE 手游，需要还原类结构、找偏移、
  做内存修改或 ESP 基础时使用。
triggers: il2cpp, unity dump, il2cpp dump, 手游逆向, UE dump, GObjects, GNames, 元数据还原, libil2cpp, 游戏dump, Il2CppDumper
---

# IL2CPP / UE 游戏 Dump（il2cpp-dump）

两条线：**Unity IL2CPP（Il2CppDumper 为主）** 与 **UE（GObjects/GNames 为主）**。

## 一、Unity IL2CPP

资产识别：`assets/bin/Data/Managed/Metadata/global-metadata.dat` + `lib/<abi>/libil2cpp.so`。
两者必须**同一版本**（meta 与 so 同包提取，混用会错位）。

### 静态（推荐先跑）

```bash
# Alpine：Il2CppDumper（NetCore 版）
./Il2CppDumper libil2cpp.so global-metadata.dat out/
# 产物：dump.cs（类/字段/方法签名）、script.json、il2cpp.h、strings
```

- 老壳/加密 meta：`Magic` 头被改 → 先 `mem_read`/`apk-reverse` 拿到运行时解密后的 meta（进程内 `cat /proc/<pid>/maps` 找映射后 dump 该区段）
- 版本不匹配报错 → 换 Il2CppDumper 兼容分支（按 Unity 版本选）

### 动态（加固/加壳目标）

- **Zygisk-Il2CppDumper**（Magisk 模块，进程启动时自动 dump 到 `/data/data/<pkg>/files/`）
- 或 attach 后从内存抓 `g_MetadataRegistration` / `s_Il2CppMetadataRegistration`（见 mobile-reverse）

### 导入分析器

- IDA：`il2cpp.h` + `script.json` → 用 `ida-py` 脚本恢复符号（Il2CppDumper 自带 `ida_with_struct_py3.py`）
- Ghidra：`il2cppdumper_ghidra` 插件
- 之后：按 `dump.cs` 的字段偏移做 `mem_read/mem_write`（如 `Player.m_Gold +0x18`）

## 二、Unreal Engine（UE4/UE5）

核心对象表：

| 表 | 作用 | 定位 |
|---|---|---|
| `GObjects` (`GUObjectArray`) | 全部 UObject | 特征：连续指针数组，`FChunkedFixedUObjectArray` |
| `GNames` (`FNamePool`) | 名字池 | 字符串块结构 `FNameEntry` |
| `GWorld` | 世界指针 | 从 `UObject::GetWorld` 回溯或特征扫描 |

流程：
1. `process_list` 找 UE 进程 → `mem_read` 拉 `libUE4.so`/`libUnreal.so` 关键区段
2. 特征扫描 GObjects/GNames（用 mem_search 找已知 UObject 指针模式）
3. 遍历 GObjects 找 `Engine/GameInstance/Level/Pawn` 类对象 → 字段偏移定位（坐标/血量/视野）
4. ESP/自瞄基础：世界坐标 → 屏幕（`ProjectWorldToScreen`）

## 三、通用排错

- dump 全是乱码 → meta/so 不同步，重取同一进程的
- 找不到 libil2cpp → 分包 App：`/data/app/~~xxx/<pkg>-xxx/lib/arm64/` 或 split APK
- 偏移不对 → 以 dump 同一会话的 maps 基址为准（ASLR：`cat /proc/<pid>/maps | grep libil2cpp`）

## 四、与 Eta 工具的配合

- 取包/解包：`apk-reverse`；内存读写：`mem_read`/`mem_search`/`mem_write`
- 强反调试（ACE 等）：`anti-frida-bypass` → `hw-trace` 硬件断点
- 交付物：dump.cs / 偏移表 / 类结构图（进 `game-cheat` 工作流）
