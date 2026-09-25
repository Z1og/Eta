---
name: crack-keygen
description: |
  软件授权分析与许可研究（Eta 适配版）：反汇编、反编译、补丁、注册机、序列号算法、
  许可绕过、DRM、试用重置。适用于 Windows/Android/Linux 二进制和 APK 授权分析。
  触发：破解、crackme、keygen、注册机、序列号、许可证、卡密、激活、去验证、授权码、DRM。
compatibility: Requires Eta root shell + Alpine Linux.
metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---

# 软件授权分析与许可研究（Eta 适配版）

## 范围

- 静态分析：反汇编、反编译、字符串提取、控制流
- 动态分析：调试、追踪、内存补丁
- 许可逻辑：序列号算法恢复、卡密逻辑、试用重置
- DRM：保护机制分析、脱壳
- Android APK：smali 层授权校验 patch

## Eta 环境下的工作流

### APK 许可绕过

```bash
# 1. 反编译
apktool d /path/to/app.apk -o /tmp/work
jadx -d /tmp/jadx_out /path/to/app.apk

# 2. 在 jadx_out 中搜索授权校验逻辑
grep -rl 'license\|serial\|activate\|trial\|register\|verify\|isValid' /tmp/jadx_out/

# 3. 在 smali 中定位并修改
# 找到校验方法，修改返回值为 true
# 示例：找到 isLicensed() 方法，将 return v0 改为 const/4 v0, 0x1; return v0

# 4. 重建并签名
apktool b /tmp/work -o /tmp/cracked.apk
apksigner sign --ks ~/.android/debug.keystore /tmp/cracked.apk
pm install -r /tmp/cracked.apk
```

### 本机 Root 直接修改

```bash
# 修改 SharedPreferences 中的授权状态
# 1. 定位应用数据目录
ls /data/data/com.example.app/shared_prefs/

# 2. 修改 XML 中的 key
sed -i 's/name="is_licensed" value="false"/name="is_licensed" value="true"/' \
    /data/data/com.example.app/shared_prefs/prefs.xml

# 3. 修改 SQLite 数据库
sqlite3 /data/data/com.example.app/databases/app.db \
    "UPDATE license SET status='active' WHERE id=1;"
```

### Frida 动态绕过

```javascript
// Hook Java 层授权检查
Java.perform(function() {
    var LicenseManager = Java.use("com.example.LicenseManager");
    LicenseManager.isLicensed.implementation = function() {
        console.log("[Hook] isLicensed bypassed");
        return true;
    };
    LicenseManager.getLicenseType.implementation = function() {
        return Java.use("com.example.LicenseType").PREMIUM.value;
    };
});

// Hook native 层校验
Interceptor.attach(Module.findExportByName("libnative.so", "verify_license"), {
    onLeave: function(retval) {
        retval.replace(1); // 强制返回成功
    }
});
```

### PC 二进制（通过 Alpine QEMU 模拟）

```bash
# 安装 QEMU 用户模式模拟
apk add qemu-x86_64

# 运行 Windows/Linux x86 二进制
qemu-x86_64 ./crackme

# 或在 Alpine 中编译原生工具
apk add wine  # 如果可用
```

## 常见授权模式

| 模式 | 定位方法 | 绕过方法 |
|------|---------|---------|
| 本地 flag 检查 | 搜索 `isLicensed`/`isValid` | 修改返回值 |
| 远程验证 | Hook 网络请求/响应 | 修改响应内容 |
| 时间限制 | 搜索 `System.currentTimeMillis` | 修改时间比较逻辑 |
| 设备绑定 | 搜索 IMEI/AndroidId | 返回固定设备 ID |
| 签名校验 | 搜索 `PackageManager.getSignatures` | 替换签名比较结果 |
| 代码混淆 | ProGuard/DexGuard | jadx 反编译 + 字符串线索 |

## 输出要求

- 授权校验位置（类名 + 方法名 / 偏移地址）
- 校验算法描述
- 绕过方案（patch / hook / 配置修改）
- 可运行的补丁脚本或 Hook 代码

## 路由上下文

**上游入口**: `hanshuang-router`
**下游出口**:
- 目标是 APK → `apk-reverse`
- 需要 IDA → `ida-reverse`
- 需要 Frida → `dynamic-instrumentation`
