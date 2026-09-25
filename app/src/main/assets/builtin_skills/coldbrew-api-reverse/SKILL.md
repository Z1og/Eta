---
name: coldbrew-api-reverse
description: 前端签名、HAR、鉴权链路、加密参数还原时使用。
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

# API 逆向

1. 抓一条完整请求：URL、方法、头、cookie、body。
2. 在入口 JS 里定位 sign / token / a_bogus / X-Bogus 生成链。
3. 抠纯算到 Python 或 Node，本地对拍签名串。
4. 分页、重试、断点续爬写进同一脚本。
5. 缺样本就用 `TARGET` `TOKEN` `SIGN` 槽位把脚本先填满。
