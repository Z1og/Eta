---
name: mobile-reverse
description: |
  Android/iOS 逆向工程（Eta 适配版）：APK/IPA 分析、Frida/Objection 工作流、
  SSL pinning 绕过、root 检测绕过、运行时 Hook。
  触发：Android逆向、iOS逆向、Frida、Objection、SSL pinning、root检测、smali、DEX。
compatibility: Requires Eta root shell + Alpine Linux + Frida.
metadata:
  source: hanshuang-codex-adapted
  eta-env: android-root
---

# 移动端逆向（Eta 适配版）

Eta 本身运行在 Android 设备上，移动端逆向是天然优势场景。

## 环境准备

```bash
# Alpine
apk add python3 py3-pip openjdk17
pip install frida-tools objection

# jadx / apktool（见 apk-reverse Skill）
```

## Android 逆向

### 1. 静态分析

```bash
# 获取已安装 APK 路径
pm path com.example.app

# 提取 APK
cp /data/app/~~.../base.apk /tmp/target.apk

# 反编译
jadx -d /tmp/jadx_out /tmp/target.apk
apktool d /tmp/target.apk -o /tmp/apktool_out

# 查看组件
dumpsys package com.example.app | grep -E 'Activity|Service|Receiver|Provider'
```

### 2. DEX 运行时修改

```bash
# 读取 DEX
cat /data/app/~~.../base.apk | unzip -p - classes.dex > /tmp/classes.dex

# 通过 /proc 直接 patch（root）
# 找到 DEX 在内存中的映射
cat /proc/$(pidof com.example.app)/maps | grep classes.dex
```

### 3. SSL Pinning 绕过

```javascript
// 通用 SSL Pinning 绕过
Java.perform(function() {
    // TrustManager
    var TrustManager = Java.use("javax.net.ssl.X509TrustManager");
    var SSLContext = Java.use("javax.net.ssl.SSLContext");

    // OkHttp CertificatePinner
    try {
        var CertificatePinner = Java.use("okhttp3.CertificatePinner");
        CertificatePinner.check.overload("java.lang.String", "java.util.List").implementation = function() {};
    } catch(e) {}

    // WebView SSL
    try {
        var WebViewClient = Java.use("android.webkit.WebViewClient");
        WebViewClient.onReceivedSslError.implementation = function(view, handler, error) {
            handler.proceed();
        };
    } catch(e) {}
});
```

或直接用 Objection：

```bash
objection -g com.example.app explore
# (objection) android sslpinning disable
```

### 4. Root 检测绕过

```javascript
Java.perform(function() {
    // SafetyNet / Play Integrity
    // GooglePlayServicesUtil
    try {
        var GooglePlayServicesUtil = Java.use("com.google.android.gms.common.GooglePlayServicesUtil");
        GooglePlayServicesUtil.isGooglePlayServicesAvailable.overload('android.content.Context').implementation = function(ctx) {
            return 0; // SUCCESS
        };
    } catch(e) {}

    // 常见 Root 检测
    var checks = [
        "com.scottyab.rootcheck",
        "com.joeykim.rootchecker",
        "eu.chainfire.supersu"
    ];
    // 逐个 Hook
});
```

## iOS 逆向（远程）

如需分析 iOS 应用，Eta 可通过网络访问远程 iOS 设备：

```bash
# SSH 到越狱 iOS 设备（需同一网络）
ssh root@ios-device-ip

# 或通过 Frida 远程
frida -H ios-device-ip:27042 -n TargetApp -l hook.js
```

## 输出要求

- 目标应用包名和版本
- 关键安全机制（SSL Pinning、Root 检测、签名校验）
- 绕过方案和 Frida 脚本
- 敏感数据位置（SharedPreferences、数据库、文件）

## 路由上下文

**上游入口**: `hanshuang-router`
**下游出口**:
- APK 深度解包 → `apk-reverse`
- .so 分析 → `ida-reverse`
- 协议分析 → `protocol-reversing`
- 游戏类 → `game-cheat`
