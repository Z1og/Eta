---
name: protocol-reversing
description: |
  网络流量解密、Protobuf 格式解析、TLV 二进制包分析、API 模拟（Eta 适配版）。
  利用 Eta 的 root 权限直接抓包和 hook 网络请求。
  触发：协议逆向、抓包、protobuf、TLV、流量分析、API 逆向、HTTP 拦截。
compatibility: Requires Eta root shell + Alpine Linux + tcpdump/wireshark-cli.
metadata:
  source: hanshuang-codex-adapted
  eta-env: android-root
---

# 协议逆向（Eta 适配版）

Eta 在 Android 设备上运行，可以直接抓取和修改本机网络流量。

## 工具安装

```bash
# Alpine
apk add tcpdump wireshark-cli tshark
pip install mitmproxy protobuf

# 可选
apk add socat nmap-ncat
```

## 抓包

### tcpdump

```bash
# 抓取所有流量
tcpdump -i any -w /tmp/capture.pcap

# 只抓 HTTP
tcpdump -i any -A -s 0 'tcp port 80 or tcp port 443'

# 抓取特定主机
tcpdump -i any host api.example.com -w /tmp/target.pcap
```

### Frida Hook 网络请求

```javascript
// Hook OkHttp
Java.perform(function() {
    var OkHttpClient = Java.use("okhttp3.OkHttpClient");
    var Request = Java.use("okhttp3.Request");
    var Response = Java.use("okhttp3.Response");

    var RealCall = Java.use("okhttp3.internal.connection.RealCall");
    RealCall.execute.implementation = function() {
        var req = this.request();
        console.log("[HTTP] " + req.method() + " " + req.url().toString());
        var headers = req.headers();
        for (var i = 0; i < headers.size(); i++) {
            console.log("  " + headers.name(i) + ": " + headers.value(i));
        }
        var body = req.body();
        if (body) {
            console.log("  [Body present]");
        }
        var resp = this.execute();
        console.log("[Resp] " + resp.code());
        return resp;
    };
});

// Hook HttpURLConnection
Java.perform(function() {
    var URL = Java.use("java.net.URL");
    var HttpURLConnection = Java.use("java.net.HttpURLConnection");
    HttpURLConnection.getInputStream.implementation = function() {
        console.log("[HTTP] GET " + this.getURL().toString());
        return this.getInputStream();
    };
});
```

## Protobuf 解析

```bash
# 从 .proto 文件生成 Python 解析器
protoc --python_out=. message.proto

# 从裸数据解析（无 .proto）
pip install blackboxprotobuf
python3 -c "
import blackboxprotobuf
msg, typedef = blackboxprotobuf.decode_message(data)
print(msg)
print(typedef)
"
```

## API 模拟

```bash
# mitmproxy 代理
mitmproxy --listen-port 8080

# 在 Android 上设置代理
settings put global http_proxy 127.0.0.1:8080

# 或通过 iptables 重定向
iptables -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to 8080
iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to 8080
```

## 本机流量分析

```bash
# 查看当前连接
ss -tnp
netstat -tnp

# DNS 查询
dig api.example.com
nslookup api.example.com

# 直接请求
curl -v https://api.example.com/endpoint
```

## 路由上下文

**上游入口**: `hanshuang-router`
**下游出口**:
- 需要解密逻辑 → `reverse-engineering`
- 需要绕过 SSL Pinning → `mobile-reverse`
- 需要 Frida Hook → `dynamic-instrumentation`
