---
name: dynamic-instrumentation
description: |
  Frida 动态 Hook、内存补丁、API 参数追踪、反调试绕过脚本生成（Eta 适配版）。
  在 Android 设备上通过 Frida 直接操作目标进程内存和函数。
  触发：Frida、Hook、动态注入、内存修改、API hook、反调试绕过。
compatibility: Requires Eta root shell + frida-server running on device.
metadata:
  source: hanshuang-codex-adapted
  eta-env: android-root
---

# Frida 动态插桩（Eta 适配版）

Eta 在 Android 设备上运行，Frida 直接在设备上工作，不需要 USB 调试和 PC 连接。

## 安装

```bash
# Alpine
pip install frida-tools objection

# 确保 frida-server 在 Android 侧运行
# Eta 的 root shell 可以启动：
# /data/local/tmp/frida-server &
```

## 基础操作

```bash
# 列出本机进程（不需要 -U）
frida-ps

# Spawn 模式
frida -f com.example.app -l script.js

# Attach 模式
frida -n "com.example.app" -l script.js

# 交互模式
frida -n com.example.app

# Trace
frida-trace -f com.example.app -j '*!*onCreate*'
frida-trace -f com.example.app -i 'strcmp'
```

## 常用 Hook 模板

### Java 方法 Hook

```javascript
Java.perform(function() {
    var Target = Java.use("com.example.TargetClass");
    Target.targetMethod.implementation = function(arg1, arg2) {
        console.log("[Hook] targetMethod called");
        console.log("  arg1: " + arg1);
        console.log("  arg2: " + arg2);
        var result = this.targetMethod(arg1, arg2);
        console.log("  result: " + result);
        return result;
    };
});
```

### Native 函数 Hook

```javascript
Interceptor.attach(Module.findExportByName("libnative.so", "target_func"), {
    onEnter: function(args) {
        console.log("target_func(" + args[0] + ", " + args[1] + ")");
    },
    onLeave: function(retval) {
        console.log("  return: " + retval);
        retval.replace(1); // 修改返回值
    }
});
```

### 内存搜索与修改

```javascript
// 搜索
var results = Memory.scanSync(ptr("0x7000000000"), 0x10000000, "48 8B 05 ?? ?? ?? ??");
results.forEach(function(match) {
    console.log("Found at: " + match.address);
});

// 读取
var intVal = Memory.readInt(ptr("0x12345678"));
var floatVal = Memory.readFloat(ptr("0x12345678"));

// 写入
Memory.writeInt(ptr("0x12345678"), 9999);
Memory.writeFloat(ptr("0x12345678"), 99.99);
```

### 反调试绕过

```javascript
// ptrace 检测
Interceptor.attach(Module.findExportByName(null, "ptrace"), {
    onEnter: function(args) {
        if (args[0].toInt32() === 31) { // PTRACE_TRACEME
            args[0] = ptr(0); // 替换为无效请求
        }
    }
});

// /proc/self/status TracerPid
Interceptor.attach(Module.findExportByName(null, "open"), {
    onEnter: function(args) {
        var path = args[0].readUtf8String();
        if (path === "/proc/self/status") {
            // 重定向到伪造文件
        }
    }
});

// Frida 检测绕过
Interceptor.attach(Module.findExportByName(null, "pthread_create"), {
    onEnter: function(args) {
        // 检测并拦截 Frida 检测线程
    }
});
```

## 内存取证

```javascript
// dump 进程内存
var pid = Process.id;
var maps = File.readAllText("/proc/" + pid + "/maps");
console.log(maps);

// 枚举模块
Process.enumerateModules().forEach(function(mod) {
    console.log(mod.name + " @ " + mod.base + " size=" + mod.size);
});

// 枚举导出
Module.enumerateExports("libnative.so").forEach(function(exp) {
    console.log(exp.name + " @ " + exp.address);
});
```

## 路由上下文

**上游入口**: `hanshuang-router`、`apk-reverse`、`game-cheat`
**下游出口**:
- 需要静态分析确认偏移 → `reverse-engineering` / `ida-reverse`
- 需要协议 Hook → `protocol-reversing`
