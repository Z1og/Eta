# Frida 动态分析模板

环境：Eta root shell + Android 设备；frida-server 以 root 运行。缺失细节用 `TARGET_PKG` / `CLASS` / `METHOD` / `HOOK_FN` / `OFFSET` 占位并继续。

## 0. 启动与附加
```bash
# 设备侧（Eta root shell）
adb push frida-server /data/local/tmp/frida-server && chmod 755 /data/local/tmp/frida-server
/data/local/tmp/frida-server &
# 主机侧 / Alpine
frida-ps -U | grep -i target          # 找包名
frida -U -f TARGET_PKG -l hook.js --no-pause   # spawn 冷启动（检测类优先）
frida -U TARGET_PKG -l hook.js                 # attach 已运行进程
```

## 1. Java 层方法 Hook（打印参数与返回值）
```javascript
Java.perform(function () {
  var C = Java.use("TARGET_PKG.CLASS");
  C.METHOD.overload('java.lang.String').implementation = function (a) {
    console.log("[*] METHOD arg=" + a);
    var r = this.METHOD(a);
    console.log("[*] METHOD ret=" + r);
    return r;
  };
});
```

## 2. 强制返回值（验证函数桩替换）
```javascript
Java.perform(function () {
  var C = Java.use("TARGET_PKG.CLASS");
  C.checkToken.implementation = function (token) {
    console.log("[*] checkToken(" + token + ") -> forced true");
    return true;
  };
  C.isVip.overload().implementation = function () { return true; };
});
```

## 3. Native 层 Hook
```javascript
Interceptor.attach(Module.findBaseAddress("libtarget.so").add(OFFSET), {
  onEnter: function (args) {
    console.log("[*] HOOK_FN arg0=" + args[0].readUtf8String());
  },
  onLeave: function (retval) {
    retval.replace(ptr(1));
  }
});
```
未导出符号：先用 `Module.enumerateExports("libtarget.so")` 枚举；找不到再从 `Module.findBaseAddress` + `OFFSET`（IDA/Ghidra 得到）定位。

## 4. SSL Pinning 绕过
```javascript
Java.perform(function () {
  var TrustManager = Java.use('javax.net.ssl.X509TrustManager');
  var TM = Java.registerClass({
    name: 'com.eta.TrustAll',
    implements: [TrustManager],
    methods: {
      checkClientTrusted: function () {},
      checkServerTrusted: function () {},
      getAcceptedIssuers: function () { return []; }
    }
  });
  Java.use('javax.net.ssl.SSLContext').init.overload(
    'javax.net.ssl.KeyManager[]', 'javax.net.ssl.TrustManager[]', 'java.security.SecureRandom'
  ).implementation = function (km, tm, sr) {
    this.init(km, [TM.$new()], sr);
  };
});
```
OkHttp 证书校验另加：`okhttp3.CertificatePinner.check.overload` 置空。

## 5. Root / 检测绕过
```javascript
Java.perform(function () {
  var File = Java.use('java.io.File');
  File.exists.implementation = function () {
    var p = this.getAbsolutePath();
    if (p.indexOf('su') >= 0 || p.indexOf('magisk') >= 0) return false;
    return this.exists();
  };
  var Runtime = Java.use('java.lang.Runtime');
  Runtime.exec.overload('java.lang.String').implementation = function (cmd) {
    if (cmd.indexOf('which') >= 0 && cmd.indexOf('su') >= 0) throw Java.use('java.io.IOException').$new('blocked');
    return this.exec(cmd);
  };
});
```

## 6. 常用枚举脚本
```javascript
// 枚举已加载类（过滤关键字）
Java.enumerateLoadedClasses({
  onMatch: function (name) { if (/verify|sign|token|crypt/i.test(name)) console.log(name); },
  onComplete: function () {}
});
// 枚举 SO 导出符号
Module.enumerateExports("libtarget.so").forEach(function (e) {
  if (/check|verify|crypt/i.test(e.name)) console.log(e.name, e.address);
});
```

## 7. crypto 追踪（抓加密前后数据）
```javascript
Java.perform(function () {
  var Cipher = Java.use('javax.crypto.Cipher');
  Cipher.doFinal.overload('[B').implementation = function (input) {
    var r = this.doFinal(input);
    console.log("[Cipher] mode=" + (this.opmode == 1 ? "ENCRYPT" : "DECRYPT") +
      " in=" + bytesToHex(input) + " out=" + bytesToHex(r));
    return r;
  });
});
```
