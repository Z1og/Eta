# ETE differential tracing — hardware execution streams, two runs, one answer

Load this when the question is **where two runs of the same code diverge** — password
accepted versus rejected, license valid versus invalid, key A versus key B — and every
software tracer either modifies the code it traces (Stalker: the DBI cache), crashes under
the load (the measured `load average 34.11` case), or delivers nothing at all (the measured
zero-event follow in `references/native-dbi-and-deobfuscation.md` §6 Failure modes). An
ARMv8.4-era core's **ETE** streams executed-branch records off-core **in hardware**, and
**TRBE** collects them self-hosted; the target's own image, its timing profile and its
`TracerPid` are untouched. This file owns the gates, the capture and the differential
method; `scripts/ete_capture.py` runs the gates and the capture, and `scripts/trace_diff.py`
is the differ — which is producer-agnostic and works on Stalker logs too.

**Claim strength.** The gates, the recipe and the capture wrapper run on the host —
`observed`. The differential reducer is host-tested on synthetic flattened-dispatcher
fixtures — `observed` for the tool, `inferred` for the method. **No ETE trace has been
captured or decoded by this repository**: no reference device here has coresight compiled in
(reference kernel 4.14.186 — the ETE gate is closed there, `observed`). The command shapes
follow the kernel's `tools/perf` CoreSight documentation and the OpenCSD project — treat
them as the documented upstream interface until your own control record decodes.

## 1. The mechanism, in the size that matters

| Piece | What it is | Why you care |
|---|---|---|
| **ETE** (Embedded Trace Extension) | the ARMv8.4 trace unit; emits executed-branch/instruction packets without instrumenting anything | nothing in the process image changes; page checksums stay clean |
| **TRBE** (Trace Buffer Extension) | per-CPU self-hosted trace buffer | per-thread tracing with no external debugger and no shared sink |
| `cs_etm` PMU | the Linux perf interface to ETE/ETM | `perf record -e cs_etm//u --per-thread` is the whole capture |
| OpenCSD | the decode library perf links against | a stock perf **records** an aux buffer it cannot **decode** — the classic silent failure |
| `scripts/trace_diff.py` | reduce + diff two decoded streams | the differ is the reusable half; it also eats Stalker `BLK` logs |

The old `etm`-only world (no TRBE) still traces, but through a **shared** sink
(`tmc_etrN`) that cannot do `--per-thread`, is frequently reserved by the vendor, and
mixes CPUs — the differential method still applies but run design gets harder. Prefer
devices with `trbeN` in `/sys/bus/coresight/devices`.

## 2. The five gates — probe before you design anything

Every closed gate fails **silently** later: an old kernel reports no PMU; a kernel without
TRBE cannot per-thread; a perf without OpenCSD decodes nothing; SELinux can deny
`perf_event_open` even to root. One command each, then believe the table:

```
python skills/apk-reverse/scripts/ete_capture.py gates --device
```

| Gate | Expected | Closed means |
|---|---|---|
| kernel era | ≥ 5.10 | the ETE/TRBE drivers are not in your kernel at all |
| coresight devices | `ete<N>` **and** `trbe<N>` in sysfs | `ete` absent: no trace unit; `trbe` absent: shared sink only |
| `cs_etm` PMU | `/sys/bus/event_source/devices/cs_etm` exists | perf cannot speak to coresight |
| perf with CORESIGHT | `perf list` shows `cs_etm` | you can record but not decode |
| permission | a control record of `true` decodes | the pipe is closed at SELinux/paranoid level |

A closed gate is a **route decision** — record it in one line per
`references/kernel-and-environment-hardening.md` §6 What the reference environment chose
(and why it generalises), and do not re-derive it mid-task. The reference environment's own
line: kernel 4.14.186 → ETE closed, permanently, on that device.

## 3. Capture

`ete_capture.py recipe` prints the full build path for a perf-with-CORESIGHT (on-device
Termux or cross). The capture itself, per-thread on TRBE:

```
python skills/apk-reverse/scripts/ete_capture.py capture --pid <PID> --seconds 10 --out a.data --control
```

`--control` is not decoration: it records a **one-instruction process** (`true`) first and
refuses to continue if that does not decode. A capture of the real target that "looks fine"
is worth nothing until the control has produced packets — this is the same discipline as the
Stalker zero-event lesson, paid for once already in this repository.

Decode to an address stream (device or host, wherever your perf lives):

```
perf script -i a.data > a.log
python skills/apk-reverse/scripts/trace_diff.py reduce --log a.log
```

Volume rule from `native-dbi-and-deobfuscation.md` §3 transfers: one module, one bounded
window. ETE traces the *whole thread*, so capture around one operation (launch to first
frame, one login attempt, one encrypt call), not a session.

## 4. The differential method

**Design the pair before touching the device.** The method answers only the question the
pair was built to ask:

1. **One changed bit of input, everything else identical.** Wrong password *character*,
   not wrong password *length* (length changes parsing). Key `0x0000...` vs `0x8000...`.
   License whose checksum byte differs. Two runs, same build, same screen state, same
   window boundaries.
2. **Reduce both streams to module-relative keys.** Raw pointers move with ASLR;
   `liba.so+0x9f6c0` is comparable across runs, `0x7b20f29f6c0` is not.
   `trace_diff.py` keeps `mod+0xoff` keys verbatim for exactly this reason.
3. **Diff and read three things** — `trace_diff.py diff --a a.log --b b.log` prints them:
   - **identical prefix span** — the shared machinery before your input bit reached
     control flow; it names where parsing/handling ends and the decision begins;
   - **first divergence** — the branch that first consumed your input difference. In a
     flattened dispatcher this is where the state constant differs;
   - **blocks unique to each side, ranked** — the input-dependent set. For "valid vs
     invalid", the B-unique list *is* what the valid path does that the invalid one
     never reaches.
4. **Interpret per obfuscation:**
   - **OLLVM flattening**: dispatcher `D` matches on both sides (it is machinery); the
     divergent `R<n>` blocks between `D`s are the real blocks the two inputs select
     between. Combined with the state-variable watchpoint in
     `references/hardware-watchpoints.md` §4, the diff gives you the state constants to
     complete the CFG recovery of `native-dbi-and-deobfuscation.md` §5 without a solver.
   - **VMP / interpreter loops**: the handler loop matches (it is the interpreter);
     unique blocks are guest-opcode handlers. The *sequence* difference between two
     inputs is a private-opcode trace — feed it to the differential mapping machinery in
     `references/vmp-differential-analysis.md` as ground truth instead of guessing.
   - **Whitebox / key-derived logic**: the unique-to-each-side sets across several key
     pairs approximate the key-dependent branch set; a branch that never appears in
     either side of any pair is not key-dependent — stop reversing it.
5. **Repeat once with the inputs swapped** if the result decides a patch. A divergence that
   moves when the inputs move is real; one that moves when it should not is capture noise.

**What the tool does not enforce** (its header says so too): same build, same address space,
same window, one-bit difference. A diff across two builds is noise with a confident
rendering.

## 5. What the obfuscator actually built — read from two open implementations

The differential reads better when you know what the two families actually emit. The facts
below are read from the public AOSP-toolchain forks (dpkg123/ollvm-aosp-toolchain, branches
`ollvm-r614150-clang23-rebase+omp` — the Hikari family — and `ollvm-r614150-clang23+omp` —
the Pluto family; xvmp commits 63fec41f/9b7cf75e; fetched 2026-10): **source text observed,
runtime behaviour inferred from it**.

| | Hikari-family flattening | Pluto-family flattening |
|---|---|---|
| state variable | `switchVar` **alloca in the frame** + a `switchVarAddr` double pointer; every state store goes through an indirect load of that pointer | `switchVar` + `switchXor` **two allocas, rolling XOR**: `enc^=delta; key^=delta; cond=enc^key` on every dispatch |
| case values | `scramble32` permuted (not 0,1,2…) | fully random per build; the true case exists only in the `switchCond` register for one instruction |
| what a memory watchpoint sees | the permuted case value — decodable against the static switch table | **noise**: the stored enc and key never contain the case |
| what a trace sees | identical in both: the block sequence — which is why the trace is the robust instrument |
| skipped functions | EH-bearing (landingpad) | EH, `callbr`, `indirectbr` |

Consequences for this file's method: the **divergent-blocks reading (§4) is family-proof** —
it never decodes the state variable. The **watchpoint-on-state recipe** is family-sensitive:
Hikari-shaped targets can be read at the (frame-resident) slot via the pointer; Pluto-shaped
targets need a breakpoint one instruction after the `switchCond` XOR to read the register
(`references/hardware-watchpoints.md` §4 carries the corrected recipe). And the AntiDebugging
pass of the Hikari family injects **raw `svc` inline asm with randomized register-setup
order and a randomized svc immediate** (source-read): byte signatures cannot survive that
randomization, but ETE's branch stream and `svc_scan.py`'s semantic site-location do not
care — locate the check by what it *is*, never by its bytes.

**xvmp (IR-level virtualizer, same repo)**: each virtualized function becomes a stub calling
`vm_exec(code, size, vm_args, num)` — bytecode bytes plus a boxed-argument array in the
module; 11 one-byte opcodes with inline-const/slot operands and 19 arithmetic sub-ops;
pointer constants materialize through a generated const/call dispatch at first use. For the
tracing routes this means: the interpreter is one dominant loop (the §4 handler reading),
the **guest inputs are observable at the `vm_args` array before interpretation starts** (a
watchpoint there sees arguments and any key material, plaintext, whatever the bytecode
carries), and each constant's bits pass through the materialization dispatch as a returned
i64 — a second, cheap observation point. `scripts/vm_handler_slice.py` consumes the trace
side and documents the handler->opcode map you can build against a *known* table like this
one.

## 6. Failure modes

- **`perf record` succeeds, `perf script` prints nothing address-like** — perf without
  OpenCSD decode, or an aux buffer that never wrapped (window too short). The control run
  separates the two in one command.
- **`gates --device` shows ete but no trbe** — shared-sink world: drop `--per-thread`,
  trace `-C <cpu>` with `cs_etm/@tmc_etr0/u`, and pin the target to that CPU
  (`taskset`-style, or accept the mixture). Expect much more post-filtering.
- **Aux overflow / gaps** — decode reports non-contiguous ranges: the window was too hot.
  Shrink it, or accept that the diff sees only a sampled path and say so in the conclusion.
- **The two runs are not comparable** — different pids with different module bases and raw
  keys: everything diverges from address zero. Re-run through `reduce`, check that the
  top-of-histogram block is shared before diffing; if the top blocks differ, the pair is
  not a pair.
- **ETE closes on the only device you have** — the honest answer, not a challenge; the
  fallback ladder for the *question* is Stalker (measured boundaries apply) or the
  watchpoint route (single addresses, no paths), never "trace harder".

## 7. Hand-offs

- Divergence found, now you need the *values* at that site → hardware watchpoint or
  breakpoint (`references/hardware-watchpoints.md`), or QBDI per-instruction replay
  (`references/native-dbi-and-deobfuscation.md` §4 Stalker, QBDI, or emulation).
- Divergence found, and you must *change* the decision with no code change →
  `references/kernel-event-injection.md`.
- The path is known and you only need to call the routine → `references/emulation-and-rpc.md`.

## Checklist

- [ ] `gates --device`: all five closed, or the closed ones recorded as route decisions
- [ ] `--control` record of `true` decodes before any real capture
- [ ] Pair design written down: same build, same window, one changed input bit
- [ ] Both logs reduced; top-of-histogram block shared before diffing
- [ ] Divergences read as: prefix span / first divergence / unique-per-side
- [ ] Input-swap repeat when the diff decides a patch
- [ ] Labels: gates+reducer `observed` (host), ETE capture/decode `unverified` here —
      the control run on your device is the only thing that upgrades it
