---
name: protocol-reversing
description: Network traffic dissection, Protobuf wire format parsing, TLV binary packet analysis, and API simulation.
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


# Protocol Reversing Skill

This skill provides procedures for network protocol reversing, binary serialization dissection, and API simulation.

## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

## Core Capabilities

1. **Protobuf Wire Format Dissection**:
   Decode raw binary protobuf payloads without `.proto` definitions:
   ```bash
   python .agents/tools/re-toolkit/cli.py decode-protobuf <hex_string_or_file>
   ```

2. **TLV (Type-Length-Value) Packet Analysis**:
   Dissect custom network frames and binary chunks:
   ```bash
   python .agents/tools/re-toolkit/cli.py decode-tlv <hex_string_or_file> --type-len 1 --len-len 2
   ```

3. **Hexdump & Stream Inspection**:
   Format raw bytes into hex and ASCII aligned output:
   ```bash
   python .agents/tools/re-toolkit/cli.py hexdump <file_or_hex> --length 256
   ```

4. **API Simulation & Mock Generation**:
   Reconstruct client request structures, signatures, and mock servers from analyzed wire formats.
