---
name: linker-fake-load-unwrapper
description: Use when analyzing Android AArch64 ELF files protected by a special linker-style fake PT_LOAD wrapper, impossible high-address LOAD segments, entry-point translation such as e_entry minus fake p_vaddr, XOR runtime decryptors, embedded ELF carving, shell-looking ELF second stages, or when a user mentions linker 加固/脱壳/通杀. Provides static and dynamic workflows, tools, and principles for recovering the real runtime image.
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


# Linker Fake-Load Unwrapper

## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

## Core idea

This protector abuses ELF program headers so the visible entry and memory layout look abnormal. Do not guess fixed offsets. Derive the real file entry from PHDR evidence, decode the entry stub, recover XOR ranges/key, then reconstruct the runtime image.

## Fast path

```sh
python <skill-dir>\scripts\linker_static_unwrap.py <target> --out-dir <out>
```

Expected outputs:

- `<target>.linker_xor_runtime.bin` — reconstructed runtime image.
- `linker_static_unwrap_report.json` — PHDRs, fake LOAD, true entry offset, XOR ranges, carved embedded ELFs, interesting strings.
- `embedded_elfs/` — candidate inner ELF payloads/interpreters.

The script intentionally refuses to proceed if it cannot find the fake LOAD or recover XOR ranges; do not hardcode offsets unless you first write down why the PHDR-derived method fails.

## Static analysis procedure

1. Parse ELF64 little-endian AArch64 header and PHDRs.
2. Find fake LOAD candidate:
   - `p_type == PT_LOAD`
   - `p_offset == 0`
   - `p_filesz > file_size`
   - high `p_vaddr` such as `0xffff0000` or huge `p_filesz`
   - `e_entry` falls inside `p_vaddr .. p_vaddr + p_filesz`
3. Translate true entry:
   - `true_entry_file_offset = e_entry - fake.p_vaddr + fake.p_offset`
4. Disassemble AArch64 stub at real file entry.
5. Recover repeated decrypt calls:
   - key usually arrives in `w3/x3` as an immediate byte.
   - target pointer and size are often loaded through PC-relative `ldr` literals into `x2` and `x1`.
   - each `bl` after those loads applies XOR to a runtime range.
6. Map target VAs through normal LOADs, not the fake one, then XOR file bytes.
7. Carve embedded `ELF` blobs and inspect sections/PHDRs/UPX markers.
8. Validate runtime by string diff, embedded ELF parse, and dynamic dump comparison when possible.

## Dynamic confirmation procedure

Use dynamic dumping when:

- Capstone cannot recover stub ranges.
- The key/ranges are computed instead of literal-loaded.
- The runtime later decrypts another stage.
- Static runtime and device behavior disagree.

On Android:

1. Run the target in a controlled directory.
2. Monitor `/proc/<pid>/maps` for file-backed or anonymous executable mappings.
3. Dump relevant mappings through `/proc/<pid>/mem` as root.
4. Prefer parent-child dumpers for short-lived children.
5. Compare dump SHA256/strings/ELF offsets with static runtime.

## Validation gates

A result is not “done” until these checks can fail and pass:

- Original hash recorded.
- Fake LOAD and true entry offset recorded.
- XOR ranges/key recorded or dynamic replacement evidence recorded.
- Runtime image exists and has hash.
- Embedded ELF offsets or absence thereof documented.
- If business logic is expected, stack/heap/memfd plaintext search performed.

## Reference routing

Read `references/linker-fake-load-principles.md` for the detailed protector model, failure modes, dynamic fallback, and reporting template.
