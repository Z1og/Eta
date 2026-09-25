---
name: coldbrew-native-reverse
description: PE/ELF/SO、JNI、OLLVM、dump、补丁时使用。
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

# Native 自动逆向

1. 复制到 SAMPLE_COPY，原文件不动。
2. strings / 导入表 / 节表先出 CHECK_FN 候选。
3. IDA 或 r2 跟校验链，记 OFFSET 和 PATCH_BYTE。
4. 动态：x64dbg / Frida 在 GetDlgItemText、strcmp、校验出口下断。
5. 交付：伪代码 + 偏移表 + 可编译还原 / patcher。
