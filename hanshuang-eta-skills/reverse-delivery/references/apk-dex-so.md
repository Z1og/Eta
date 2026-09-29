# APK / DEX / SO 逆向流程模板

环境：Eta Alpine chroot（`apk add` / `pip install` 装工具）；目标 `TARGET.apk`。缺失细节用 `TARGET` / `CLASS` / `OFFSET` / `SYMBOL` 占位并继续。

## 1. 静态解包
```bash
apk add apktool jadx openjdk17            # Alpine 工具安装
apktool d TARGET.apk -o out/              # 资源 + smali
jadx -d out-src/ TARGET.apk               # Java 源码（可读性优先）
# 关键定位：AndroidManifest（入口 Activity / 权限 / meta-data）、res/values/strings.xml
grep -rn "checkSign\|verify\|license\|activation" out-src/ | head -50
```

## 2. 校验点定位
- 常见关键词：`signature` / `verify` / `checkSign` / `getPackageManager` / `LICENSE` / `activation` / `trial`。
- smali 层：`grep -rn "Landroid/content/pm/Signature" out/smali/`，顺着调用链找 `if-eqz` / `if-nez` 判定寄存器。
- SO 层：`strings libtarget.so | grep -i "sign\|licen\|activ"`，配合 `SYMBOL` 导出表定位。

## 3. smali patch 与重打包
```smali
# 典型 patch：把校验失败分支的 return 0 / goto fail 改为恒真
.method public checkSign()Z
    const/4 v0, 0x1
    return v0                 # 原: invoke-xxx; move-result v0; return v0
.end method
```
```bash
apktool b out/ -o patched.apk
keytool -genkeypair -v -keystore eta.keystore -alias eta -keyalg RSA -keysize 2048 -validity 10000
apksigner sign --ks eta.keystore --out patched-signed.apk patched.apk
adb install -r patched-signed.apk
```

## 4. SO 层分析与 patch
- 入口：`readelf -d libtarget.so` 看 JNI 入口 `Java_包名_类_方法`；`nm -D` / `Module.enumerateExports` 列 `SYMBOL`。
- Ghidra/IDA 打开看 `OFFSET` 处关键分支，patch 字节：
  - ARM Thumb 恒真返回：`MOV R0, #1; BX LR` → `01 20 70 47`
  - ARM64 恒真返回：`MOV W0, #1; RET` → `20 00 80 52 C0 03 5F D6`
- 动态验证用 Frida `Interceptor.attach` 改返回值（见 `references/frida-hook.md` 第 3 节），确认后再落静态 patch。

## 5. 加固 / 脱壳提示
- 壳特征：`libjiagu`（360）、`libDexHelper`（梆梆）、`libSecShell`（爱加密）、`libexecmain`（腾讯乐固）。
- 脱壳：`pip install frida-dexdump`，frida-server 启动后 `frida-dexdump -U -f TARGET_PKG`，dump 出的 dex 回填 `out/` 再走 jadx。
- 脱壳后的类缺失时，优先用 Frida 动态补齐行为，不硬啃加固壳。

## 6. 交付物清单
- 定位结论（文件/类/方法/偏移）、patch 前后 diff、重打包签名产物路径、验证步骤与实测结果。
