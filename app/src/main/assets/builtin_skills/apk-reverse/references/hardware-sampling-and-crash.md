# Hardware sampling and crash forensics — SPE data flow, BRBE branch records, and turning addresses into symbols

Load this when the question is **data flow rather than control flow** ("which code touches this
buffer" without knowing the buffer's address yet — that is SPE's job, not a watchpoint's), when
**a crash's stack unwind cannot be trusted** (obfuscated code, frame-pointer omission, stacks
smashed by the fault itself — that is BRBE's job), or when you hold a list of runtime addresses
that must become **symbols and static offsets** before they mean anything (that is
`scripts/trace_addr2sym.py`'s job, and it closes the loop for every other tool here).

**Claim strength.** The ELF walker behind `trace_addr2sym.py` is host-tested against a real
cross-compiled aarch64 shared library and a hand-built minimal ELF — `observed`. SPE/BRBE
mechanisms and their kernel interfaces follow the ARM ARM and the kernel/perf documentation —
`inferred`; **no SPE sample or BRBE record was ever captured on a device by this repository**
(no reference device carries either feature) — the producer side is `unverified` and every
route below starts with its gate probe.

## 1. SPE — the watchpoint that does not need to know the address

The Statistical Profiling Extension (FEAT_SPE, ARMv8.2+, present from Cortex-A75/A55-class
cores onward — far older hardware than ETE) samples instruction execution at a programmable
interval and records, per sample: the **PC**, the **data virtual address** the instruction
touched (for loads/stores), latency, and microarchitectural events (cache/TLB misses, branch
mispredict). Two properties make it a reverse-engineering instrument rather than a profiler:

1. **Data address + PC in one record.** That answers "which code reads/writes key-shaped
   buffers" *without a priori address knowledge* — the exact gap a watchpoint has (a
   watchpoint is precise and cheap but must be told where to look; SPE is sampled and noisy
   but asks nothing in advance). The workflow: capture SPE around the operation, group
   samples by data address, find addresses whose accessors are concentrated in the hardened
   `.so`, then graduate those addresses to a hardware watchpoint for precision.
2. **It runs whether or not anyone cooperates.** No symbols, no relocations, no hooks; the
   aux buffer fills from user space with `perf record -e arm_spe_0// --per-thread`
   (`arm_spe<N>` PMU; kernel `CONFIG_ARM_SPE_PMU`; probe
   `ls /sys/bus/event_source/devices/`). Decode with the same perf-with-OpenCSD-class build
   as ETE (`scripts/ete_capture.py recipe` builds both), or import the capture into
   Perfetto, which renders SPE records natively.

Gates and limits: SPE is **sampling** — a buffer touched twice may appear zero times; treat
absence as weak evidence only (the same discipline as trace absence in
`references/native-dbi-and-deobfuscation.md` §7 Reporting what a trace proves). The sampling
interval trades coverage for overhead; and the data-address field is truncated/tagged on some
implementations — verify on one known access first.

## 2. BRBE — the last N branches before the crash

The Branch Record Buffer Extension (FEAT_BRBE, ARMv9.2+, Cortex-X3/A715-class cores and
newer) is hardware that **continuously records the last N taken branches** (from→to pairs,
32–64 records) into a buffer the kernel can read. It is the ARM equivalent of Intel's last
branch record, and it exists precisely for the question a crash asks: *what was the control
flow that led here?* — answered by hardware even when the stack is gone, the frame pointers
were optimized out, or the code was flattened so the unwinder wanders.

Two ways to get at it, both gated:

| Route | What | Gate |
|---|---|---|
| **perf branch stacks** | `perf record -b`/`-j any`; on arm64 kernels with BRBE support (6.1-era merges; `inferred`) the branch-stack samples are hardware-fed by BRBE | kernel with BRBE perf support; probe `perf record -j any -o /dev/null -- true` |
| **crash-time read** | your own tracer (ptrace stop at the fatal signal, or a kernel-side module) reads the BRBE records before the process is reaped | kernel exposure of the records (`unverified` — the registers are EL1-accessed; a KPM-side reader belongs to `references/kernel-event-injection.md`) |

Use it when: the tombstone's backtrace is the thing you do not trust (`scripts/native_crash.py`
prints the stack split by owner — when the frames sit in wrong modules or anonymous regions,
that is the signal); the crash is inside flattened/virtualized code whose stack is decorative;
or the death is a deliberate fault (`references/native-tamper-and-suicide.md`) and the branch
records name the site that jumped to it. On hardware without BRBE, the honest fallback is the
ETE aux buffer: stop the process at the crash (never let it die), and read the tail of the
trace — same answer, more plumbing, gates in `references/ete-diff-tracing.md` §2 The five
gates.

## 3. The crash loop: three sources, one order of asking

1. **Tombstone/logcat first** — `scripts/native_crash.py`: signal, fault address, registers,
   frames split app-vs-system, and the arranged-fault flag. Cheap, always available.
2. **If the stack is not trustworthy** — branch records (BRBE if the gate opens; ETE tail
   otherwise). This is the *real* control flow, and it survives obfuscation by construction.
3. **Then symbolize everything** — every PC from 1 or 2 through
   `scripts/trace_addr2sym.py`, with the maps pulled **in the same process lifetime**:

```
python skills/apk-reverse/scripts/trace_addr2sym.py --hits hwwatch_hits.log --maps maps.txt --elf liba.so
python skills/apk-reverse/scripts/trace_addr2sym.py --elf liba.so --addrs 0x7b20001234c0 --base 0x7b2000000000
python skills/apk-reverse/scripts/trace_addr2sym.py --trace vm_trace.log --mod libvm.so --elf libvm.so
```

The offset-space rules that decide whether the number you paste into IDA is right (vaddr vs
file offset, `p_vaddr != p_offset` payloads) are stated once, in the tool's header — read them
before distrusting a symbol.

## 4. What this adds to the deobfuscation routes

- **OLLVM**: SPE finds the arithmetic that touches key material without a trace
  (`references/native-dbi-and-deobfuscation.md` §1's table, data-flow half); BRBE/ETE-tail
  recover the real path into a flattened function that crashes — the state machine's taken
  states, read from hardware, no unwinder.
- **VMP/xvmp-class**: SPE answers "which handler sequence touches the argument slots" when
  the bytecode is opaque (`vm_exec`'s boxed arguments live in a plain array —
  `scripts/vm_handler_slice.py`'s header records where); a crash inside the interpreter
  keeps its branch records, so the fatal guest opcode is identifiable from the last
  handler segment.
- **Anti-debug/anti-hook inserts**: a deliberate `svc`-based check (the Hikari-family
  AntiDebugging pass injects inline-assembly syscalls with randomized immediate fields on
  Darwin targets, and links precompiled anti-debug IR elsewhere — source-read, `observed`
  text / `inferred` behaviour) is invisible to byte-signature scans precisely because the
  fields are random. Hardware-level observation does not care: ETE shows the branch into
  and back out of the check; a breakpoint at the *semantic* site (the `svc` itself,
  located by `scripts/svc_scan.py`) beats a signature by construction.

## 5. Failure modes

- **`arm_spe_0` absent from `/sys/bus/event_source/devices/`** — kernel without
  `CONFIG_ARM_SPE_PMU` or a core without FEAT_SPE (pre-ARMv8.2): closed gate, one line,
  move on; the watchpoint route still exists if you can name the address.
- **`perf record -j any` on arm64 returns samples with empty branch stacks** — the kernel
  took the software path (no BRBE backend); the records are call-chain-ish, not
  hardware branch pairs. Check `perf report`'s branch-stack content on a known-hot control
  flow before trusting a negative.
- **SPE shows nothing at an address you know is touched** — sampling missed it. Lower the
  interval (more samples, more overhead), or graduate to a watchpoint for that one address.
- **Symbolization disagrees with the disassembler** — the offset space was wrong, not the
  tool: re-read the header's vaddr/file-offset rules, check `--offs-are-file`, and confirm
  the module's load base from the same process lifetime. A runtime-materialized library
  (lib_map flags those) has a base that never appears in any APK.

## 6. Hand-offs

- Full-path capture, divergence diffs → `references/ete-diff-tracing.md`.
- Single-address precision, zero code modification → `references/hardware-watchpoints.md`.
- Reading BRBE from a kernel module, crash-time record reads →
  `references/kernel-event-injection.md`.
- Triage of the crash itself (signal, fault, arranged-fault) →
  `references/native-tamper-and-suicide.md`, `scripts/native_crash.py`.

## Checklist

- [ ] Gate probed before design: `arm_spe_0` present? BRBE branch stacks real or software?
- [ ] SPE: one known access verified visible before absence is interpreted as anything
- [ ] Crash loop in order: tombstone → branch records → symbolize; maps from the same
      process lifetime
- [ ] Offsets pasted into a disassembler cross-checked against the tool's offset-space rules
- [ ] Labels: addr2sym host-`observed`; SPE/BRBE producer `unverified` here — your gate
      probe is the first real measurement
