# 逆向工程 Field Notes（Eta 适配版）

## 二进制类型快速判定

| Magic | 类型 |
|-------|------|
| 7f 45 4c 46 | ELF |
| 4d 5a | PE (Windows) |
| ce fa ed fe | Mach-O 32-bit |
| cf fa ed fe | Mach-O 64-bit |
| 50 4b 03 04 | ZIP/APK/JAR |
| ca fe ba be | Java class / Mach-O FAT |
| 1f 8b | gzip |
| fd 37 7a 58 5a | xz |

## Android 上的反调试绕过

### ptrace self-attach
```bash
# 检测方法：目标进程已 ptrace 自身
# 绕过：在 Frida 中 hook ptrace 返回 0
```

### android.os.Debug.isDebuggerConnected()
```javascript
Java.perform(function() {
    var Debug = Java.use("android.os.Debug");
    Debug.isDebuggerConnected.implementation = function() {
        return false;
    };
});
```

### Frida 检测绕过
```javascript
// 1. 重命名 frida-server 线程
// 2. Hook socket connect 拦截 27042 端口检测
// 3. Hook fopen 拦截 /proc/self/maps 中 frida 特征
Interceptor.attach(Module.findExportByName(null, "fopen"), {
    onEnter: function(args) {
        var path = args[0].readUtf8String();
        if (path && path.indexOf("frida") !== -1) {
            args[0] = Memory.allocUtf8String("/dev/null");
        }
    }
});
```

## 常见 CTF 逆向模式

1. **明文 flag** — `strings | grep flag`
2. **XOR 加密** — 已知明文攻击
3. **自定义 VM** — 提取 opcode 映射
4. **反调试 + flag check** — Frida hook 绕过
5. **多阶段解密** — dump 中间状态
6. **信号处理器** — strace 观察信号流

## 快速工具命令

```bash
# ELF 分析
readelf -h binary      # ELF 头
readelf -S binary      # 节表
readelf -s binary      # 符号表
objdump -d binary      # 反汇编

# radare2
r2 binary
aaa; afl; pdf @ main

# Frida 快速 hook
frida -n target -e 'Interceptor.attach(ptr("0xADDR"), {onEnter: function(a){console.log(a[0])}})'
```
