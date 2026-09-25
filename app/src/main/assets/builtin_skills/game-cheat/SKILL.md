---
name: game-cheat
description: |
  游戏客户端工程（Eta 适配版）：进程内存、注入、渲染、输入、检测绕过。
  在 Android 设备上通过 root shell 和 Frida 实现游戏修改器、ESP、速度修改等。
  触发：cheat、trainer、aimbot、esp、wallhack、speedhack、外挂、修改器、游戏辅助。
compatibility: Requires Eta root shell + Frida + target game installed on device.
metadata:
  source: hanshuang-codex-adapted
  eta-env: android-root
---

# 游戏客户端工程（Eta 适配版）

Eta 运行在 Android 设备上，天然适合做游戏内存分析和修改。通过 root shell 和 Frida 直接操作目标游戏进程。

## Eta 环境优势

- **本机即是游戏设备**：直接操作运行中游戏的内存
- **Root 权限**：`/proc/PID/mem` 完全可读写
- **Frida 直接注入**：无需 USB 调试，直接 spawn/attach
- **Alpine Linux**：编译辅助工具、分析脚本

## 工具安装

```bash
# Alpine
apk add python3 py3-pip gcc g++
pip install frida-tools

# frida-server 需要在 Android 侧运行
# Eta 的 root shell 可以启动 frida-server
```

## 内存读写

### 通过 /proc/PID/mem

```bash
# 获取 PID
PID=$(pidof com.game.target)

# 读取内存映射
cat /proc/$PID/maps | grep -E 'heap|anon'

# 读取内存（需要 root）
dd if=/proc/$PID/mem bs=1 skip=$((0xHEXADDR)) count=256 2>/dev/null | xxd

# 写入内存
# 使用 Frida 或编写 C 小工具
```

### 通过 Frida

```javascript
// 内存扫描
var ranges = Process.enumerateRangesSync('rw-');
ranges.forEach(function(range) {
    Memory.scan(range.base, range.size, "E8 ?? ?? ?? ?? 85 C0", {
        onMatch: function(address, size) {
            console.log("[Found] " + address);
        },
        onComplete: function() {}
    });
});

// 读取内存
var value = Memory.readInt(ptr("0x12345678"));

// 写入内存
Memory.writeInt(ptr("0x12345678"), 99999);

// 指针链追踪
var base = Module.findBaseAddress("libil2cpp.so");
var offset = 0x1A2B3C;
var value = Memory.readInt(base.add(offset));
```

## 常见游戏修改模式

### Unity IL2CPP 游戏

```javascript
// 1. 找到 il2cpp 基址
var il2cpp = Module.findBaseAddress("libil2cpp.so");

// 2. 通过偏移定位字段
// 偏移通常需要从 global-metadata.dat 解析
var healthOffset = 0x1A2B3C;
var healthAddr = il2cpp.add(healthOffset);
Memory.writeFloat(healthAddr, 9999.0);

// 3. Hook 方法
Interceptor.attach(il2cpp.add(0x2C3D4E), {
    onEnter: function(args) {
        // args[0] = this, args[1] = damage
        console.log("Damage: " + args[1].toInt32());
        args[1] = ptr(0); // 无敌
    }
});
```

### 内存特征码扫描

```javascript
// 扫描特征码
var pattern = "F3 0F 10 ?? ?? ?? ?? ?? 0F 2F ?? 76 ?? F3 0F 58";
Process.enumerateModules().forEach(function(mod) {
    Memory.scan(mod.base, mod.size, pattern, {
        onMatch: function(addr) {
            console.log("[" + mod.name + "] " + addr);
        },
        onComplete: function() {}
    });
});
```

### 速度修改

```javascript
// Hook time 相关函数
Interceptor.attach(Module.findExportByName(null, "clock_gettime"), {
    onLeave: function(retval) {
        // 修改时间流逝速度
    }
});

// 或直接修改 Unity Time.timeScale
Java.perform(function() {
    var Time = Java.use("UnityEngine.Time");
    // 通过 il2cpp 反射
});
```

## 反作弊绕过

### ACE / 反作弊检测

```javascript
// 1. 隐藏 Frida 特征
// 重命名 frida-server 线程
// 2. Hook 检测函数
Interceptor.attach(Module.findExportByName("libace.so", "anti_cheat_check"), {
    onLeave: function(retval) {
        retval.replace(0); // 返回未检测到
    }
});

// 3. 隐藏 /proc/self/maps 中的 frida 特征
Interceptor.attach(Module.findExportByName(null, "open"), {
    onEnter: function(args) {
        var path = args[0].readUtf8String();
        if (path && path.indexOf("/proc/self/maps") !== -1) {
            // 重定向到过滤后的副本
        }
    }
});
```

### Root 检测绕过

```javascript
Java.perform(function() {
    // Su 检测
    var File = Java.use("java.io.File");
    File.exists.implementation = function() {
        var path = this.getAbsolutePath();
        if (path.indexOf("su") !== -1 || path.indexOf("magisk") !== -1) {
            return false;
        }
        return this.exists();
    };
});
```

## 输出要求

- 目标游戏进程名和 PID
- 修改的内存地址/偏移
- Hook 的函数和方法
- 修改前后值对比
- 反作弊绕过措施
- 可复用的 Frida 脚本

## 路由上下文

**上游入口**: `hanshuang-router`
**下游出口**:
- 需要 IL2CPP 元数据解析 → `reverse-engineering`
- 需要 .so 深入分析 → `ida-reverse`
- 需要反作弊绕过 → `dynamic-instrumentation`
