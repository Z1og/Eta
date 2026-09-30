# FOFA 资产测绘 — 在 Eta / Alpine 下使用

FOFA MCP（`references/fofa-mcp/fofa.py`）是一个 stdio Python 服务，用于检索公网资产。
Eta 运行在 Android + Alpine chroot 环境，按如下方式使用：

## 方式一：直接调 FOFA REST API（推荐，零依赖）
```bash
# Alpine 内
curl -s "https://fofa.info/api/v1/search/all?email=<FOFA_EMAIL>&key=<FOFA_KEY>&qbase64=$(printf 'domain="example.com"' | base64 -w0)&size=100"
```
回包 JSON 的 `results` 为 `[host, ip, port, ...]` 数组。密钥从环境变量读取，**禁止**写进对话或技能文件。

## 方式二：跑 FOFA MCP 服务
```bash
# Alpine 内安装 uv
apk add uv python3
cd /data/local/tmp/eta
# 把 references/fofa-mcp/ 拷到工作区
cp -r <skill>/references/fofa-mcp ./fofa-mcp
export FOFA_EMAIL=... FOFA_KEY=...
uv run --directory ./fofa-mcp python fofa.py
```
服务以 stdio 通信；Eta 暂无内建 MCP client 时，建议用方式一，或在 Alpine 内用
`uv run --directory ./fofa-mcp python -c "from fofa import ...; ..."` 直接调用其函数。

## 限流与多账号
多账号（主号→backup→backup2）轮换与限流闸见 `references/rules/dig-scope-workflow.md` §2.1.4。
搜资产用 FOFA，不要自己 curl 全集团；一种子闭环：搜一个种子→去重去废去非存活→挖完剩余活面才换种。
