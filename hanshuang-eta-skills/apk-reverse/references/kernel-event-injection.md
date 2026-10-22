# Kernel-side event injection — the driver as your hands inside the target

Load this when the observation or the change you need is **impossible to deliver from the
target's own process** — `TracerPid` must stay 0, the code must not change by one byte, the
decision must flip at a PC you choose, and it must all happen without any thread or mapping
the target could enumerate. A kernel-side driver can do this because the arm64 debug
registers can be programmed **on the target's behalf** through the kernel's
perf/breakpoint layer, and the overflow callback runs holding the interrupted context's
`pt_regs`. This file owns the primitive map and the composition recipes;
`scripts/kernel_instrument_scaffold.py` generates the templates.

**Division of labour, so nothing gets said twice.** `references/kernel-and-environment-hardening.md`
owns *hiding from detection* — which root scheme, which module, when escalation is wrong;
its §4a gate table applies to this file unchanged. `references/hardware-watchpoints.md`
owns the *ptrace* delivery of the same hardware, including its honest `TracerPid` column.
This file is the third leg: **instrumentation and injection from the kernel**, when the
other two deliveries are unacceptable.

**Claim strength.** `gates`/`generate`/`verify` run on the host — `observed` for the
generator. **No kernel-side file this scaffold emits has been compiled or loaded anywhere,
by anyone in this repository's passes** — every generated file says so in its own header,
and the register-rewrite action is explicitly labelled experimental. The API surface
(`register_user_hw_breakpoint` / `perf_event_create_kernel_counter`,
`PERF_TYPE_BREAKPOINT` with `bp_type`/`bp_addr`/`bp_len`) is the kernel's documented
GPL-exported interface —
`inferred` as a mechanism, `unverified` as a route until a device with the matching gate
compiles and loads it.

## 1. The primitive map — what each route can actually do

| primitive | KPM (KernelPatch) | out-of-tree LKM | eBPF | Zygisk module |
|---|---|---|---|---|
| hardware watchpoint on the target task, hit logged to dmesg | yes (via perf bp layer, symbols resolved at load) | yes (`register_user_hw_breakpoint`, GPL-exported) | **no** — no perf breakpoint creation from BPF | no |
| rewrite a register at the trap (flip a branch decision, no code change) | template action, **experimental** | template action, **experimental** | no | no |
| read/write the target's memory from outside it | yes | yes (`access_process_vm`) | read via helpers, write rarely | in-process only (it *is* the process) |
| force a syscall return value | yes — see `kernelsu_syscall_mask.py` | yes (syscall table / inline hooks) | observe; rewrite only for `ALLOW_ERROR_INJECTION` targets | no (wrong layer) |
| deliver a signal at a chosen moment | yes | yes | yes | in-process |
| visible to the target (`TracerPid`, foreign threads, code deltas) | **none** | **none** | **none** | in-process, pre-init_array |

Read the table as instructions: the **only** route that observes *and* alters with zero
in-process footprint is a kernel module; eBPF is an observer; Zygisk is early *inside* the
process — the right answer for init_array-early checks
(`references/kernel-and-environment-hardening.md` §4 Kernel-level interception — the map,
and the version gate), not for invisible watchpoints.

## 2. The recipes — "wish-mode", assembled from the other two files

These are compositions, not new mechanisms. Each names its deliverable and its fallback,
because a kernel route is never the first thing to try — it is what you escalate to when
the cheaper route's ledger (`references/hardware-watchpoints.md` §1 The stealth ledger)
says the target will see you.

**Recipe 1 — "give me the plaintext key, whatever wraps the cipher."**
Watch the buffer the crypto fills, kernel-armed so the RASP never notices, and read the
buffer at the hit. Steps: locate the buffer statically (`svc_scan.py`, decompiler) →
`kernel_instrument_scaffold.py generate --pid <PID> --watch w:<ADDR>:8:w` → load on a
device with the KPM/LKM gate closed → `dmesg` gives the filling PC → at the next hit read
the buffer (`/proc/<pid>/mem` from root outside the process is enough; no injection needed).
*Fallback:* ptrace watchpoint when `TracerPid` is not read by the target — cheaper, same hit.

**Recipe 2 — "make the check pass without changing one byte."**
The differential (`references/ete-diff-tracing.md` §4) names the branch that first consumes
the valid/invalid difference. Instead of patching that branch (which the page checksum
watches), arm an execution breakpoint at its PC from the kernel and rewrite the condition
register at the trap: the decision flips, the image stays byte-identical.
`generate --break b:<PC> --set-reg x0=0x1`. *This is the experimental action* — the
register rewrite in a perf overflow callback was never run by this repository; validate on
a device you can reboot, and prefer patching the code when the checksum threat model does
not actually include self-verification of that page.

**Recipe 3 — "trace it, but leave TracerPid alone."**
ETE capture needs no ptrace at all (it is perf + aux buffers), and kernel watchpoints need
none either — so the entire observation half of this toolkit composes into a zero-ptrace
session: `ete_capture.py capture` for paths, kernel watchpoints for addresses, dmesg as the
event bus. What remains visible is the perf events themselves (a determined kernel-state
reader can enumerate them) — say so rather than claiming true invisibility.

## 3. The scaffold and its gates

```
python skills/apk-reverse/scripts/kernel_instrument_scaffold.py gates
python skills/apk-reverse/scripts/kernel_instrument_scaffold.py generate \
    --out work/kwatch --pid <PID> --watch w:0x7b2000123450:8:w --break b:0x7b20001234c0 --set-reg x0=0x1
python skills/apk-reverse/scripts/kernel_instrument_scaffold.py verify --dir work/kwatch
```

The gates are the §4a table's: **KPM** needs a KernelPatch-patched boot image plus
`aarch64-none-elf-gcc`; **LKM** needs kernel source matching the exact vermagic; the
**userspace module** is an honest carrier that can arm nothing. One gate is new and belongs
to this file: the kernel's **perf breakpoint layer must own the debug registers**
(`CONFIG_HW_PERF_EVENTS`); if it is compiled out, `register_user_hw_breakpoint` does
not exist and both templates close — check
`ls /sys/bus/event_source/devices/breakpoint` on the
device. Design notes carried in the generated files, all load-bearing: the callback runs
on **every hit, system-wide** — the generated template prints **unfiltered** on entry, so
adding a cheap first-statement filter is a required change before you rely on any hit
(the same lesson the syscall-mask template learned publicly); symbols in a KPM are
declared without `extern` or the loader rejects the module; and a slot that fails to arm
is logged, never silently skipped.

## 4. When to stop — the same stop rules, extended one row

Every rule of `references/kernel-and-environment-hardening.md` §7 When to stop escalating —
do not counter-escalate a userspace check with kernel development
applies verbatim, plus this file's own:

| Situation | Do this | Not this |
|---|---|---|
| The check sees `TracerPid` but not perf/kernel state | kernel watchpoint + ETE, zero ptrace | patching the boot image to hide one field |
| The decision site is known and the page is not self-verified | byte patch (`references/native-and-so.md`) | kernel register rewrite |
| You want a kernel route on a 4.14/Magisk device | record the closed gate, use ptrace watchpoint or Zygisk | "just build a quick module" |
| The register rewrite did not take effect | report it — experimental means the first run is a measurement | a second, larger rewrite from the same unverified template |

## Checklist

- [ ] Cheaper route's ledger consulted first (hardware-watchpoints §1); kernel chosen
      because the target's detection actually demands it, not because it is stronger
- [ ] `gates` run; KPM/LKM/perf-bp gates answered for **this** device, closed ones recorded
- [ ] Watch specs passed `plan`-style validation at generation (window, alignment)
- [ ] Control: one deliberate touch on a watched global produces one dmesg line before the
      real window — an unverified template owes this before any hit is trusted
- [ ] Callback filter first, no sleeping, `printk` only (plus the explicitly-labelled
      experimental register action, only on a device you can reboot)
- [ ] Every kernel file still carries its `NOT COMPILED AND NOT LOADED` banner after your
      edits (`verify --dir`), or the banner is replaced by your own measurement
- [ ] Deliverable sentence re-read: a kernel-route result is a **rooted-device result**,
      labelled a fallback unless the task asked for exactly that
