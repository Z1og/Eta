---
name: apk-reverse
description: |
  在 Eta 的 Android 环境下做 APK 逆向。适用于 APK 解包、Java 反编译、smali 修改、重打包、
  Frida 动态 Hook，以及按需切换到 so/native 分析。本 Skill 利用 Eta 的 root shell 和
  Alpine Linux 环境直接在设备上操作，无需外部 PC。
  触发：APK逆向、Android逆向、jadx、apktool、Frida Hook、smali修改、重打包。
compatibility: Requires Eta root shell + Alpine Linux with jadx/apktool/frida installed.
metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---

# APK 逆向（Eta 适配版）

在 Eta 的 Android root shell + Alpine Linux 环境下直接在设备上完成 APK 逆向。

## Eta 环境优势

- **本机即是目标设备**：不需要 ADB 连接外部机器，`adb shell` 就是本机
- **Root 权限**：可读写 `/data/app/`、`/data/data/`、`/data/local/tmp/`
- **Alpine Linux**：`apk add` 安装 jadx、apktool、frida、radare2 等工具
- **直接 Hook**：Frida 不需要 `-U` USB 模式，直接 attach 本机进程

## 工具安装（Alpine Linux）

```bash
# 进入 Alpine 环境（Eta 自动处理）
# 或通过 shell 工具在 root shell 中执行

# Python 与基础工具
apk add python3 py3-pip openjdk17 git

# 逆向工具
pip install frida-tools objection
apk add radare2

# jadx — 从 GitHub Release 下载
wget -q https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip -O /tmp/jadx.zip
unzip -q /tmp/jadx.zip -d /opt/jadx && chmod +x /opt/jadx/bin/jadx
ln -sf /opt/jadx/bin/jadx /usr/local/bin/jadx

# apktool — 下载 JAR
wget -q https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar -O /opt/apktool.jar
echo '#!/bin/sh' > /usr/local/bin/apktool
echo 'java -jar /opt/apktool.jar "$@"' >> /usr/local/bin/apktool
chmod +x /usr/local/bin/apktool
```

## 工作流

### 1. Triage — 快速判断 APK 构成

```bash
# APK 信息
aapt dump badging /path/to/app.apk | head -20

# 解包
apktool d /path/to/app.apk -o /tmp/apktool_out

# Java 反编译
jadx -d /tmp/jadx_out /path/to/app.apk

# 查看 Manifest 关键组件
grep -E '(activity|service|receiver|provider|permission)' /tmp/apktool_out/AndroidManifest.xml | head -40

# 检查 native 库
ls -la /tmp/apktool_out/lib/*/ 2>/dev/null
```

### 2. Java 逻辑分析

从 `jadx_out` 中读取 Java 反编译代码：

```bash
# 搜索关键类
grep -rl 'login\|sign\|encrypt\|cipher\|token\|root\|certificate' /tmp/jadx_out/ | head -20

# 搜索网络相关
grep -rl 'okhttp\|retrofit\|webview\|volley' /tmp/jadx_out/ | head -20

# 搜索 JNI
grep -rl 'System.loadLibrary\|native' /tmp/jadx_out/ | head -20
```

用 Eta 的 **file** 工具直接读取反编译后的 Java 文件内容。

### 3. Smali 修改与重打包

```bash
# 修改 smali（用 Eta file 工具编辑）
# 示例：修改 root 检测返回值
# 在 /tmp/apktool_out/smali*/ 中定位目标方法

# 重建 APK
apktool b /tmp/apktool_out -o /tmp/ rebuilt.apk

# 对齐（需要 Android build-tools）
zipalign -v 4 /tmp/rebuilt.apk /tmp/aligned.apk

# 签名（调试签名）
apksigner sign --ks ~/.android/debug.keystore --out /tmp/signed.apk /tmp/aligned.apk

# 安装
adb install -r /tmp/signed.apk
# 注意：本机 adb shell = 本机，可以用 pm install
pm install -r /tmp/signed.apk
```

### 4. Frida 动态 Hook

Eta 运行在本机，Frida 不需要 USB 模式：

```bash
# 列出本机进程
frida-ps

# Spawn 模式启动
frida -f com.example.app -l /tmp/hook.js

# Attach 正在运行的进程
frida -n "com.example.app" -l /tmp/hook.js

# Trace 特定方法
frida-trace -f com.example.app -j '*!*certificate*/i'
frida-trace -f com.example.app -j '*!*root*/i'
```

常用 Hook 脚本模板：

```javascript
// Hook Java 方法
Java.perform(function() {
    var clazz = Java.use("com.example.LoginActivity");
    clazz.checkPassword.implementation = function(pwd) {
        console.log("[Hook] checkPassword called: " + pwd);
        return true; // 强制返回 true
    };
});

// Hook SSL Pinning
Java.perform(function() {
    var TrustManager = Java.use("javax.net.ssl.X509TrustManager");
    var SSLContext = Java.use("javax.net.ssl.SSLContext");
    // ... 绕过 SSL pinning
});

// Hook native 函数
Interceptor.attach(Module.findExportByName("libnative.so", "Java_com_example_NativeLib_sign"), {
    onEnter: function(args) { console.log("sign called, arg0: " + args[0]); },
    onLeave: function(retval) { console.log("sign returned: " + retval); }
});
```

### 5. 本机 Root 优势操作

```bash
# 直接读取已安装 APK 的数据
ls -la /data/data/com.example.app/
cat /data/data/com.example.app/shared_prefs/*.xml

# 提取已安装 APK
pm path com.example.app  # 获取 APK 路径
cp /data/app/~~.../base.apk /tmp/target.apk

# 读取进程内存映射
cat /proc/$(pidof com.example.app)/maps

# 直接修改运行中进程的内存
# 通过 Eta 的 shell 工具使用 /proc/PID/mem
```

### 6. Native .so 分析分流

APK 中包含关键 `.so` 时的判断信号：
- Java 层只是 JNI 包装
- 核心签名/加密逻辑不在 Java
- `System.loadLibrary()` 后关键逻辑消失

切到 native 分析：
- 快速 triage → 用 `radare2`（Alpine `apk add radare2`）
- 深入分析 → 用 `ida-reverse` Skill（需 IDA 远程或 headless 模式）

```bash
# radare2 快速分析 .so
r2 -A /tmp/lib/arm64-v8a/libnative.so
afl          # 列出函数
ii           # 导入
iE           # 导出
pdf @ sym.Java_com_example_NativeLib_sign
```

## 输出要求

完成后至少说明：
- 入口组件与关键类
- 关键逻辑在 Java、smali 还是 `.so`
- 已确认的敏感点：登录、签名、root、SSL、WebView、JNI
- 如果做了 patch，说明改了什么
- 如果做了 Hook，说明 Hook 了哪个类/方法/导出函数

## 路由上下文

**上游入口**: `hanshuang-router`
**下游出口**:
- 核心逻辑在 `.so` → `ida-reverse` 或 `reverse-engineering`
- 需要 Frida 进阶 → `dynamic-instrumentation`
- 需要协议分析 → `protocol-reversing`
