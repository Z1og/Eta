# Hardware watchpoints — observing memory without touching the code

Load this when you need to know **who writes or reads a memory location**, and every
in-process tool you have modifies the thing it observes: an inline hook is caught by the
library's own page checksum, a Stalker follow rewrites the code it traces, and the target
dies before your hook exists. An arm64 hardware watchpoint compares every load/store
address **in silicon** and traps on match: no code byte changes, no thread appears, no
mapping is added. This file owns the mechanism, its honest stealth ledger, the traps, and
the procedure; `scripts/hw_watch.py` is the tool; `references/ete-diff-tracing.md` owns
full-path tracing, and `references/kernel-event-injection.md` owns the ptrace-free delivery.

**Claim strength.** The WCR/BCR encoders and the emitted helper are host-tested, and the
helper **compiles clean as a static aarch64 ELF** (cross gcc 16.2, `-static`) — `observed`
on the host. **No watchpoint has been armed on a device by this repository's passes** — the
ptrace dance follows the kernel ABI (arm64 `NT_ARM_HW_WATCH`/`NT_ARM_HW_BREAK` regsets,
`TRAP_HWBKPT` siginfo) and is `unverified` until the control run below passes on your
device. Slots, capacities and per-thread semantics are ARM DDI 0487 / kernel
`arch/arm64/kernel/hw_breakpoint.c` facts — `inferred` from documented interfaces.

## 1. The stealth ledger — read it before choosing this route

| delivery | code modified | foreign thread/mapping | `TracerPid` | survives page checksums | per-hit cost |
|---|---|---|---|---|---|
| inline hook (Frida `Interceptor`) | yes | no | no | **no — caught by construction** | ~none |
| Stalker follow | yes (DBI copy) | yes (agent + code cache) | no | no | large constant |
| **ptrace hardware watchpoint** (this file) | **no** | **no** | **yes — visible** | **yes** | near-zero |
| kernel-armed hardware watchpoint (`kernel-event-injection.md`) | no | no | **no** | yes | near-zero |

Two conclusions follow, and both are instructions: a watchpoint is the answer to
*self-checksumming* code (there is nothing in the image to find), and it is **not** an
answer to a target that reads `TracerPid` over a raw `svc` — for that, the kernel route
exists, with its own gate. Never present the ptrace route as invisible; it is invisible to
the process image, not to `/proc`.

## 2. The mechanism, in the size that matters

Each arm64 core carries a small bank of debug comparators — typically **6 breakpoints
(instruction addresses) and 4 watchpoints (data addresses)**; the kernel reports the true
counts and `scripts/hw_watch.py plan`'s device-side counterpart reads them
(`dbg_info & 0xff` from the `NT_ARM_HW_WATCH` regset). Three hardware rules decide what is
expressible:

1. **A watchpoint covers at most 8 bytes, inside one aligned 8-byte window** (byte-select
   field `BAS`). A watch at `0x...4` with length 8 crosses the window and needs two slots.
   `hw_watch.py plan` refuses it locally instead of letting the device.
2. **Execution breakpoints need a 4-byte-aligned PC** (A64 instruction width), and matching
   uses the whole address.
3. **Slots are per-thread.** A process with N threads needs the watchpoint armed N times,
   and a thread created later runs unarmed until your tracer catches its first stop — which
   is why the emitted helper seizes with `PTRACE_O_TRACECLONE` and arms every new thread at
   birth.

The two canonical encodings (unit-tested; everything else is a variation):

```
watch 8 bytes, load+store, EL0 : WCR = 0x1FFD   (BAS=0xff, LSC=both, PAC=EL0, E=1)
break, address match, EL0      : BCR = 0x3F05   (BAS all-ones, PMC=EL0, BT=unlinked, E=1)
```

Delivery is the standard ptrace regset ABI — `PTRACE_SEIZE` + `PTRACE_INTERRUPT` every
thread, `PTRACE_SETREGSET` with `NT_ARM_HW_WATCH`/`NT_ARM_HW_BREAK`, `PTRACE_CONT`, then
`waitpid` for a `SIGTRAP` whose `si_code == TRAP_HWBKPT (4)` and whose `si_addr` is the
data address that matched. On hit you read `NT_PRSTATUS` for the register file. To *continue*
from an execution breakpoint you must disable the slot, `PTRACE_SINGLESTEP`, re-enable —
without that dance the trap re-fires on the same PC forever.

## 3. Procedure

**1. Plan on the host** — validate alignment and see the exact encodings:

```
python skills/apk-reverse/scripts/hw_watch.py plan --addr 0x7b2000123456 --len 8 --access w
python skills/apk-reverse/scripts/hw_watch.py plan --break 0x7b20001234c0
```

**2. Emit the helper** (self-contained C; NDK clang on the host, or Termux clang on the
device — build commands are in the generated README):

```
python skills/apk-reverse/scripts/hw_watch.py emit --out work/hwwatch
```

**3. Gates, then the control run — a watchpoint that has never fired is not armed.** Pick a
global you can touch on demand (a flag the app exposes, or a location you write through
`/proc/<pid>/mem` from the host as root) and confirm one `HIT` arrives with a sensible PC
**before** you spend the window on the real address. The measured Stalker lesson transfers
exactly: zero events from a correctly-installed instrument is a real failure mode
(`references/native-dbi-and-deobfuscation.md` §6 Failure modes), and the only defence is a
producer you have already seen fire.

```
python skills/apk-reverse/scripts/hw_watch.py run --pkg <PKG> --addr 0x... --len 8 --access rw --seconds 20
python skills/apk-reverse/scripts/hw_watch.py decode --hits hwwatch_hits.log --maps maps.txt
```

`decode` turns each hit's PC into `module+offset` given a `/proc/<pid>/maps` file (pull it
in the same process lifetime — bases move with ASLR) or a `lib_map.py` JSON.

**4. Read the hits the way a trace taught you to**: a hit's PC sequence is caller evidence;
run it through `scripts/trace_diff.py reduce` if you collected several, and correlate with
`lib_map.py` for which library actually owns the address.

## 4. Where a watchpoint beats a hook — the recipes

- **The key that never appears in a readable form.** OLLVM-substituted and VM-wrapped crypto
  still materialises plaintext in a buffer before use. Watch the buffer you identified
  statically (the JNI argument location, the `memcpy` destination) with `--access w`: the
  PC that fills it is the decryptor, obfuscation irrelevant — control flow does not matter
  to a comparator.
- **The state variable of a flattened dispatcher.** Family-sensitive, and the difference is
  real (read from two open implementations — Hikari vs Pluto flattening — in
  `references/ete-diff-tracing.md` §5 What the obfuscator actually built — read from two
  open implementations): in the Hikari shape the state is a frame-resident slot written
  through an indirect pointer — watch the slot and each `HIT` PC is a real block's tail,
  with the written (permuted) state value readable afterwards. In the Pluto shape the state
  is stored **rolling-XOR encoded**: memory only ever holds noise, so the recipe is a
  breakpoint one instruction after the dispatch decode (`switchCond = enc ^ key`) and the
  condition register is read at the trap, not from memory. If you cannot tell which family
  you have, the block-sequence routes (ETE diff, `scripts/vm_handler_slice.py`) never need
  the decoded value at all. That decoding is half of the CFG recovery in
  `references/native-dbi-and-deobfuscation.md` §5.
- **The self-modifying / self-decrypting region.** Watch the code page itself with
  `--access x`-adjacent breakpoints on entry and `--access w` on the page: the PC that
  writes executable memory is the unpacker, whatever the section headers claim.
- **"Does this check even read my patch?"** Watch the patched location: zero reads during a
  run that enforces the old behaviour is direct evidence the check reads something else —
  cheaper than a full trace and answerable in one window.

A stack slot cannot be watched this way if the frame moves between runs; watch the *caller's*
stable global or the buffer the function receives instead. The 8-byte window limit means
large structs need either the one field that matters or several slots.

## 5. Failure modes and traps

- **`READY ... watch_slots=0`** — the kernel masked hardware debug (some vendor kernels
  reserve the slots for their own telemetry, or the SoC reports none). That is a closed
  gate; record it and move to `references/kernel-event-injection.md` or the ETE route.
- **Only the arming thread traps.** The watchpoint is per-thread: if you armed one tid and
  the work happens on another, you get silence, not an error. The helper arms every thread,
  but check the `ARM tid=` lines against `ls /proc/<pid>/task` before trusting a quiet run.
- **A second tracer wins or you break one.** Only one tracer is allowed; if frida is
  attached, `PTRACE_SEIZE` fails with `EPERM`, and vice versa. Detach everything else first.
- **Swallowed signals.** A naive loop that resumes with `PTRACE_CONT(tid, 0, 0)` drops the
  target's own signals (its crash handlers, its SIGALRMs) and the app misbehaves in ways you
  will blame on the target. The helper forwards non-debug `SIGTRAP`s and other signals, and
  uses `PTRACE_LISTEN` for group stops; if you write your own loop, this is the part to get
  right, not the encodings.
- **Root, and only root.** `ptrace` of an app process from the shell needs `su`; the helper
  checks and says so.
- **Detach cleanly or leave the target traced forever**: slots cleared, every tid detached.
  The helper does this on every exit path including the caps; a `kill -9` of the helper
  leaves `PTRACE_O_EXITKILL` to do the damage — do not do that.

## 6. Hand-offs

- Full execution paths, not single addresses → `references/ete-diff-tracing.md`.
- `TracerPid` must stay 0 → `references/kernel-event-injection.md` (same hardware, armed
  from the kernel, no tracer relationship).
- You only need to *call* the routine you located → `references/emulation-and-rpc.md`.
- The watchpoint found the site and you must neutralise a terminate path → the rules in
  `references/native-tamper-and-suicide.md` apply unchanged.

## Checklist

- [ ] `plan` accepted the address locally (no window crossing, PC aligned)
- [ ] `emit` + build succeeded; helper runs and prints `READY` with slots > 0
- [ ] **Control run passed**: one deliberate touch produced one `HIT` with a sane PC
- [ ] maps pulled from the same process lifetime; `decode` resolves module+offset
- [ ] Every `ARM tid=` line accounted for against `/proc/<pid>/task`
- [ ] No other tracer (frida, lldb) attached during the window
- [ ] Strength labels on every conclusion: encoders `observed` (host), device behaviour
      `unverified` until your control run, kernel-armed route `unverified` until compiled
