---
name: capture-mitm
description: |
  移动端抓包与中间人链路：tcpdump 落 PCAP、mitmproxy 透明代理、系统证书安装、
  SSL Pinning 绕过（objection/Frida 脚本）、双向认证处理、抓包结果解析与会话重组。
  当需要抓 App 的 HTTPS 流量、分析接口、绕过证书绑定或导出会话时使用。
triggers: 抓包, 中间人, mitm, mitmproxy, tcpdump, ssl pinning, 证书绑定, 双向认证, 解包, 流量分析, 代理抓包, pcap
---

# 抓包与中间人链路（capture-mitm）

三条主路，按目标强度选：**tcpdump 纯采集 → mitmproxy 改包 → Frida 解绑定**。

## 一、tcpdump 纯采集（无改包，最稳）

```bash
# Alpine（apk add tcpdump）
tcpdump -i any -s 0 -w /data/local/tmp/eta/cap.pcap host <目标IP或域名解析> &
# 跑完拉取分析
tshark -r cap.pcap -Y 'http2 or tls.handshake' -T fields -e frame.time -e ip.dst -e tls.handshake.extensions_server_name
```

适用：只需要 SNI/流量画像、或 App 有强 pinning 且暂不想动它。

## 二、mitmproxy 中间人（看明文/改包）

```bash
# Alpine：apk add mitmproxy（或 pip install mitmproxy）
mitmproxy --mode transparent --listen-port 8080 --showhost
# 透明转发：iptables 把 443 重定向到 8080
iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to-ports 8080
```

证书安装：
1. `mitmproxy` 首次启动生成 `~/.mitmproxy/mitmproxy-ca-cert.pem`
2. 推入系统证书库（Android 14：`/apex/com.android.conscrypt/cacerts/` 需 overlay 或 Magisk 模块；低版本 `/system/etc/security/cacerts/` 按 `openssl x509 -subject_hash_old` 命名）
3. 用户证书不足：App targetSdk ≥24 只信系统 CA

改包：mitmproxy 的 `-s rewrite.py` 或在 Web UI `flow.intercept`。

## 三、SSL Pinning 绕过（看不到明文时）

```bash
# objection（推荐，覆盖 OkHttp/Conscrypt/Flutter 常见 pinning）
objection -g <package> explore --startup-command "android sslpinning disable"

# 或 Frida 通用脚本（Alpine 内 frida-tools）
frida -n <package> -l ssl-unpinning.js -q
```

- 双向认证（client cert）：先用 `capture` 找到 App 内嵌 p12（资源目录 `*.p12/*.pfx`），`openssl pkcs12` 导出后 `mitmproxy --certs` 带上
- QUIC/HTTP3 走不通时：`iptables -A OUTPUT -p udp --dport 443 -j DROP` 强制回落 TCP

## 四、结果解析与会话重组

```bash
# PCAP → 会话
tshark -r cap.pcap -q -z conv,tcp
# 提取 HTTP 对象
tshark -r cap.pcap --export-objects http,./objs
# mitmproxy 导出：Web UI → File → Export（har / flows）
```

回包里的 token/id/host → 进 SRC/渗透清单（配 src-hunt / api-security）。

## 五、与 Eta 工具的配合

- 浏览器侧改包：优先 `browser_use` 的 `set_intercept`/`add_intercept_replace`（无需代理，直接换响应）——简单场景不用起 mitmproxy
- App 内流量：tcpdump/mitmproxy 走 Alpine；证书安装命令需 root
- 顽固 pinning → `anti-frida-bypass`（检测面）→ `hw-trace`（硬件断点）

## 六、红线

- 只抓自有/授权目标；抓到的凭证只做测试不进交付物正文（脱敏按 vuln-report-format）
