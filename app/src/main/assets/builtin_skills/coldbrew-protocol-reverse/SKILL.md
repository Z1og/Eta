---
name: coldbrew-protocol-reverse
description: 自定义协议、TCP/UDP 帧、回放与字段还原时使用。
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

# 协议分析

1. 固定长度头还是 TLV，先画字段表。
2. 对齐魔数、长度、校验、序列号。
3. 写可回放客户端：连 HOST、发 PAYLOAD、打回显。
4. 加密层单独拆：密钥从样本 / 内存 / 配置三处找。
