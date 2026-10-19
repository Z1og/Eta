#!/usr/bin/env python3
"""hw_watch.py -- invisible memory watchpoints and execution breakpoints on arm64 Android.

What this is, and the honest stealth ledger
-------------------------------------------
An arm64 core carries a small number of *hardware* debug slots (typically 6
breakpoints, 4 watchpoints). A watchpoint compares the effective data address of
every load/store the core executes -- in silicon, before the instruction
retires -- and traps to the kernel when it matches. Nothing about the target's
code or memory changes: no inline patch, no trampoline, no injected thread, no
foreign mapping, no DBI code cache. A library that checksums its own pages,
scans for hooks, or counts threads finds nothing, because there is nothing to
find in its process image.

The one thing a ptrace-based watchpoint *does* leave is ``TracerPid`` in
``/proc/<pid>/status``: the tracer relationship is a kernel fact, and this tool
does not hide it. The ledger, in full:

| delivery            | code modified | foreign thread/mapping | TracerPid | survives page checksums |
|---------------------|---------------|------------------------|-----------|-------------------------|
| inline hook         | yes           | no                     | no        | NO -- caught by design  |
| Frida Stalker       | yes (copy)    | yes (agent + cache)    | no        | no                      |
| ptrace hw watchpoint| NO            | no                     | YES       | yes                     |
| kernel hw watchpoint| NO            | no                     | no        | yes (kernel-event-injection.md) |

Limits that decide whether the tool fits the question: 8-byte maximum per
watchpoint, byte-select inside one aligned 8-byte window only, per-thread slots
(every thread must be armed, new threads arrive unarmed until caught), and the
process must be ptraceable at all (root; nobody else may already be tracing it).

What runs where
---------------
``plan``    pure host: encode DBGWCR/DBGBCR values for an address and check the
            alignment rules bite before anything touches a device.
``emit``    pure host: write the self-contained on-device helper (C, one file,
            no dependency beyond bionic).
``gates``   host probe: is adb here, is an arm64 compiler here -- plus the exact
            one-liners to run on the device to close the device-side gates.
``run``     device: build (NDK clang by default, Termux clang on request), push,
            run the helper as root, stream hits to a log.
``decode``  pure host: turn a hit log into module+offset, given a maps file.

Measured vs unverified
----------------------
The encoders and the plan/emit/decode paths are host-tested
(tests/unit/test_hw_watch.py), and the emitted helper **compiles clean as a
static aarch64 ELF** on a cross toolchain (observed: gcc 16.2, `-static`, zero
errors). **No watchpoint has been armed on a device by this repository's
passes** -- the ptrace dance, the slot capacity probe and the clone-catch path
follow the kernel ABI (arch/arm64 ptrace + hw_breakpoint) and must be validated
on your device with the control procedure in references/hardware-watchpoints.md
before any hit is trusted.

Examples
  python hw_watch.py plan --addr 0x7b2000123456 --len 16 --access w
  python hw_watch.py plan --break 0x7b20001234c0
  python hw_watch.py emit --out work/hwwatch
  python hw_watch.py gates
  python hw_watch.py run --pkg <PKG> --addr 0x7b2000123456 --len 8 --access rw \\
      --seconds 20 --out hits.log
  python hw_watch.py decode --hits hits.log --maps maps.txt
"""
import argparse
import json
import os
import shutil
import subprocess
import sys

# ---------------------------------------------------------------------------
# Kernel ABI constants (arm64 Linux). These are stable uapi numbers, not
# toolchain facts: include/uapi/linux/ptrace.h, include/uapi/linux/elf.h,
# arch/arm64/include/uapi/asm/ptrace.h.
# ---------------------------------------------------------------------------
PTRACE_CONT = 7
PTRACE_SINGLESTEP = 9
PTRACE_ATTACH = 16
PTRACE_DETACH = 17
PTRACE_GETSIGINFO = 0x4202
PTRACE_SETSIGINFO = 0x4203
PTRACE_GETREGSET = 0x4204
PTRACE_SETREGSET = 0x4205
PTRACE_SEIZE = 0x4206
PTRACE_INTERRUPT = 0x4207
PTRACE_LISTEN = 0x4208
PTRACE_O_EXITKILL = 0x01000000
PTRACE_O_TRACECLONE = 0x00000008

NT_PRSTATUS = 1
NT_ARM_HW_BREAK = 0x402
NT_ARM_HW_WATCH = 0x403

TRAP_HWBKPT = 4            # siginfo si_code for a hardware break/watchpoint hit
USER_PT_REGS_BYTES = 272   # 31 x-regs + sp + pc + pstate, 8 bytes each
MAX_SLOTS = 16             # struct user_hwdebug_state.dbg_regs[] size

# DBGWCR_EL1 field map (ARM DDI 0487): E[0] enable; PAC[2:1] privilege access
# control (0b10 = EL0/unprivileged only -- the value a userspace watchpoint
# wants); LSC[4:3] load/store control (01 load, 10 store, 11 both); BAS[12:5]
# byte-address-select inside the 8-byte window named by DBGWVR.
WCR_E = 1
WCR_PAC_EL0 = 0b10 << 1
WCR_LSC = {"r": 0b01 << 3, "w": 0b10 << 3, "rw": 0b11 << 3}
WCR_BAS_SHIFT = 5

# DBGBCR_EL1 for an unlinked address-matching A64 execution breakpoint:
# BAS[13:8] = 0b111111 (whole-address match), PMC[2:1] = 0b10 (EL0), BT = 0.
BCR_ADDR_MATCH_EL0 = (0x3F << 8) | WCR_PAC_EL0 | WCR_E

# arm64 user_pt_regs offsets (bytes) -- used by the helper and by decode.
REG_OFFSETS = {("x%d" % i): i * 8 for i in range(31)}
REG_OFFSETS.update({"sp": 31 * 8, "pc": 32 * 8, "pstate": 33 * 8})


class PlanError(ValueError):
    """An address/length/access combination the hardware cannot express."""


def encode_watch(addr, length, access):
    """Return (wvr, wcr) for a watchpoint, or raise PlanError with the reason.

    The hardware rule that bites: BAS selects bytes inside ONE aligned 8-byte
    window. A watch that starts at 0x...4 with length 8 crosses the window and
    cannot be expressed in one slot (move the address or use two).
    """
    if length not in range(1, 9):
        raise PlanError("watch length must be 1..8 bytes, got %d" % length)
    if access not in WCR_LSC:
        raise PlanError("access must be one of r/w/rw, got %r" % access)
    off = addr & 7
    if off + length > 8:
        raise PlanError(
            "watch [0x%x + %d] crosses its aligned 8-byte window "
            "(addr&7=%d): shift the address or split across two slots"
            % (addr, length, off))
    bas = (((1 << length) - 1) << off) << WCR_BAS_SHIFT
    wcr = bas | WCR_LSC[access] | WCR_PAC_EL0 | WCR_E
    return addr & ~0x7, wcr


def encode_break(addr):
    """Return (bvr, bcr) for an execution breakpoint at a 4-byte-aligned PC."""
    if addr & 3:
        raise PlanError(
            "breakpoint PC 0x%x is not 4-byte aligned -- A64 instructions are"
            % addr)
    return addr, BCR_ADDR_MATCH_EL0


def canonical_examples():
    """The two encodings everything else is a variation of (unit-tested)."""
    return encode_watch(0, 8, "rw")[1], BCR_ADDR_MATCH_EL0


# ---------------------------------------------------------------------------
# The on-device helper. One C file, no dependency beyond bionic; every ptrace
# call goes through syscall() so no header availability can change behaviour.
#
# STATUS: the syntax is host-checked (gcc -fsyntax-only where a compiler
# exists) and the ABI numbers are the kernel's uapi values, but this helper
# has not been run on a device by this repository's passes. Validate with the
# control procedure in references/hardware-watchpoints.md first.
# ---------------------------------------------------------------------------
HWWATCH_C = r'''/* hwwatch_helper.c -- arm64 hardware watchpoint/breakpoint driver.
 *
 * Generated by skills/apk-reverse/scripts/hw_watch.py -- do not edit by hand.
 * TEMPLATE NOT YET DEVICE-TESTED BY THIS REPOSITORY. Build it, run the control
 * procedure (references/hardware-watchpoints.md) before trusting a hit, and
 * remember: ptrace makes TracerPid non-zero in /proc/<pid>/status.
 *
 * Usage (as root):
 *   hwwatch <pid> w:<addr>:<len>:<r|w|rw> [w:... ...] [b:<addr> ...]
 *           [--max-hits N] [--seconds S]
 * Output grammar (one logical line per event):
 *   READY pid=<pid> watch_slots=<n> break_slots=<n>
 *   ARM   tid=<tid> watch=<k> break=<k>
 *   HIT   tid=<tid> kind=watch|break pc=0x.. addr=0x.. sp=0x.. lr=0x.. x0=0x.. .. x7=0x..
 *   EXIT  tid=<tid> code=<n>
 *   DONE  hits=<n> reason=max-hits|seconds|exit|clean-detach|error
 */
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <time.h>
#include <dirent.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <sys/stat.h>
#include <fcntl.h>

/* --- kernel ABI numbers (arm64); kept local so headers cannot disagree --- */
#define PTRACE_CONT          7
#define PTRACE_SINGLESTEP    9
#define PTRACE_DETACH       17
#define PTRACE_GETSIGINFO 0x4202
#define PTRACE_GETREGSET  0x4204
#define PTRACE_SETREGSET  0x4205
#define PTRACE_SEIZE      0x4206
#define PTRACE_INTERRUPT  0x4207
#define PTRACE_LISTEN     0x4208
#define PTRACE_O_EXITKILL    0x01000000
#define PTRACE_O_TRACECLONE  0x00000008
#define NT_PRSTATUS          1
#define NT_ARM_HW_BREAK  0x402
#define NT_ARM_HW_WATCH  0x403
#ifndef TRAP_HWBKPT
#define TRAP_HWBKPT          4
#endif
#define PTRACE_EVENT_CLONE   3
#define PTRACE_EVENT_STOP  128

/* Slot layout is the kernel ABI (arch/arm64/include/uapi/asm/ptrace.h,
 * struct user_hwdebug_state): per slot a 64-bit ADDRESS register first, then a
 * 32-bit CONTROL register, then 4 bytes of pad -- 16 bytes per slot. Swapping
 * the two arms every slot with the control bits as the address. */
struct hwslot { uint64_t wvr; uint32_t wcr; uint32_t pad; };
struct hwstate { uint32_t dbg_info; uint32_t pad; struct hwslot slot[16]; };
struct uregs { uint64_t x[31]; uint64_t sp, pc, pstate; };

static long pt(long req, long pid, long addr, long data) {
    return syscall(SYS_ptrace, req, pid, (void *)addr, (void *)data);
}

/* ---- configuration filled from argv ---- */
struct spec { int is_break; uint64_t addr; int len; char access[3]; };
static struct spec specs[16];
static int nspec = 0, max_hits = 0;
static long run_seconds = 0;

#define MAXTIDS 256
static int tids[MAXTIDS];            /* known threads of the target */
static int ntids = 0;
static int target_pid = 0;
static long hits = 0;

static void say(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    vfprintf(stdout, fmt, ap);
    fputc('\n', stdout);
    fflush(stdout);
    va_end(ap);
}

static int known(int tid) {
    for (int i = 0; i < ntids; i++) if (tids[i] == tid) return 1;
    return 0;
}

static void remember(int tid) {
    if (tid <= 0 || known(tid) || ntids >= MAXTIDS) return;
    tids[ntids++] = tid;
}

static int get_regset(int tid, int nt, void *buf, size_t len) {
    struct iovec iov = { buf, len };
    return (int)pt(PTRACE_GETREGSET, tid, nt, (long)&iov);
}
static int set_regset(int tid, int nt, const void *buf, size_t len) {
    struct iovec iov = { (void *)buf, len };
    return (int)pt(PTRACE_SETREGSET, tid, nt, (long)&iov);
}

/* Read the slot capacity the kernel reports (dbg_info & 0xff). */
static int slot_capacity(int tid, int nt) {
    struct hwstate st;
    memset(&st, 0, sizeof st);
    if (get_regset(tid, nt, &st, sizeof st) != 0) return -1;
    return (int)(st.dbg_info & 0xff);
}

static int wcap = 0, bcap = 0;   /* slot capacities, probed once */

/* Arm every watch/break spec on one stopped thread. Watchpoints and
 * breakpoints live in separate tables on arm64, so slot indexes of the two
 * kinds may overlap. */
static int arm_thread(int tid) {
    struct hwstate w, b;
    int nw = 0, nb = 0;
    memset(&w, 0, sizeof w); memset(&b, 0, sizeof b);
    for (int i = 0; i < nspec; i++) {
        const struct spec *s = &specs[i];
        if (s->is_break) {
            if (nb >= bcap) { say("ARMFAIL tid=%d slot=break%d capacity=%d", tid, nb, bcap); return -1; }
            b.slot[nb].wcr = (0x3Fu << 8) | (0x2u << 1) | 1u;  /* BAS all-ones, PMC=EL0, E */
            b.slot[nb].wvr = s->addr;
            nb++;
        } else {
            if (nw >= wcap) { say("ARMFAIL tid=%d slot=watch%d capacity=%d", tid, nw, wcap); return -1; }
            unsigned bas = (((1u << s->len) - 1u) << (s->addr & 7)) << 5;
            unsigned lsc = s->access[0] == 'r' && s->access[1] == 'w' ? 3u
                         : (s->access[0] == 'r' ? 1u : 2u);
            w.slot[nw].wcr = bas | (lsc << 3) | (0x2u << 1) | 1u;
            w.slot[nw].wvr = s->addr & ~0x7ull;
            nw++;
        }
    }
    if (nw && set_regset(tid, NT_ARM_HW_WATCH, &w, 8 + 16 * nw) != 0) return -1;
    if (nb && set_regset(tid, NT_ARM_HW_BREAK, &b, 8 + 16 * nb) != 0) return -1;
    say("ARM tid=%d watch=%d break=%d", tid, nw, nb);
    return 0;
}

static int clear_thread(int tid) {
    struct hwstate z;
    memset(&z, 0, sizeof z);
    set_regset(tid, NT_ARM_HW_WATCH, &z, 8 + 16 * 1);
    set_regset(tid, NT_ARM_HW_BREAK, &z, 8 + 16 * 1);
    return 0;
}

/* Undo everything: clear slots, detach every seized thread. Every error
 * path after the first successful PTRACE_SEIZE must go through here:
 * PTRACE_O_EXITKILL means an exit without detach SIGKILLs the target --
 * an analysis failure must not terminate the app under analysis. */
static void release_all(void) {
    for (int i = 0; i < ntids; i++) clear_thread(tids[i]);
    for (int i = 0; i < ntids; i++) pt(PTRACE_DETACH, tids[i], 0, 0);
}

/* Enumerate /proc/<pid>/task, seizing and interrupting every thread. */
static int seize_all(void) {
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/task", target_pid);
    DIR *d = opendir(path);
    if (!d) { say("DONE hits=%ld reason=error detail=no-such-process", hits); return -1; }
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        int tid = atoi(e->d_name);
        if (!tid || known(tid)) continue;
        if (pt(PTRACE_SEIZE, tid, 0, PTRACE_O_EXITKILL | PTRACE_O_TRACECLONE) != 0) {
            if (errno == EPERM)
                say("FATAL seize tid=%d EPERM: not root, or already traced", tid);
            else
                say("FATAL seize tid=%d errno=%d", tid, errno);
            closedir(d);
            /* partial seize: threads seized before this one must not be
             * EXITKILLed when we exit -- detach them now */
            for (int i = 0; i < ntids; i++) pt(PTRACE_DETACH, tids[i], 0, 0);
            return -1;
        }
        remember(tid);
    }
    closedir(d);
    for (int i = 0; i < ntids; i++)
        pt(PTRACE_INTERRUPT, tids[i], 0, 0);
    /* wait for each to report stopped; other tids' stops stay queued and are
     * reaped by the main loop */
    for (int i = 0; i < ntids; i++) {
        int st;
        pid_t r;
        do { r = waitpid(tids[i], &st, __WALL); } while (r < 0 && errno == EINTR);
        (void)r;
    }
    return 0;
}

static void print_hit(int tid, int is_break, struct uregs *r, uint64_t waddr) {
    printf("HIT tid=%d kind=%s pc=0x%llx addr=0x%llx sp=0x%llx lr=0x%llx "
           "x0=0x%llx x1=0x%llx x2=0x%llx x3=0x%llx x4=0x%llx x5=0x%llx "
           "x6=0x%llx x7=0x%llx\n",
           tid, is_break ? "break" : "watch",
           (unsigned long long)r->pc, (unsigned long long)waddr,
           (unsigned long long)r->sp, (unsigned long long)r->x[30],
           (unsigned long long)r->x[0], (unsigned long long)r->x[1],
           (unsigned long long)r->x[2], (unsigned long long)r->x[3],
           (unsigned long long)r->x[4], (unsigned long long)r->x[5],
           (unsigned long long)r->x[6], (unsigned long long)r->x[7]);
    fflush(stdout);
}

/* Step over an execution breakpoint: disable the slot, single-step, re-arm.
 * Without this dance the re-enabled trap fires on the same PC forever. */
static int step_over(int tid, uint64_t pc) {
    struct hwstate b;
    int nb = 0;
    memset(&b, 0, sizeof b);
    for (int i = 0; i < nspec; i++)
        if (specs[i].is_break && specs[i].addr != pc) {
            b.slot[nb].wcr = (0x3Fu << 8) | (0x2u << 1) | 1u;
            b.slot[nb].wvr = specs[i].addr;
            nb++;
        }
    set_regset(tid, NT_ARM_HW_BREAK, &b, 8 + 16 * 16);
    pt(PTRACE_SINGLESTEP, tid, 0, 0);
    int st;
    waitpid(tid, &st, __WALL);
    return arm_thread(tid);
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: hwwatch <pid> w:0xADDR:LEN:rw [b:0xADDR] ... [--max-hits N] [--seconds S]\n"); return 2; }
    target_pid = atoi(argv[1]);
    if (target_pid <= 0) return 2;
    for (int i = 2; i < argc; i++) {
        if (!strncmp(argv[i], "--max-hits", 10)) { max_hits = atoi(argv[++i]); continue; }
        if (!strncmp(argv[i], "--seconds", 9))   { run_seconds = atol(argv[++i]); continue; }
        struct spec s;
        memset(&s, 0, sizeof s);
        if (argv[i][0] == 'b' && argv[i][1] == ':') {
            s.is_break = 1;
            s.addr = strtoull(argv[i] + 2, NULL, 0);
            if (s.addr & 3) { fprintf(stderr, "break PC must be 4-byte aligned\n"); return 2; }
        } else if (argv[i][0] == 'w' && argv[i][1] == ':') {
            char *p = argv[i] + 2, *q;
            s.addr = strtoull(p, &q, 0);
            if (*q != ':') return 2;
            s.len = (int)strtol(q + 1, &q, 0);
            if (*q != ':') return 2;
            snprintf(s.access, sizeof s.access, "%s", q + 1);
            unsigned off = s.addr & 7;
            if (s.len < 1 || s.len > 8 || off + s.len > 8) {
                fprintf(stderr, "watch [0x%llx+%d] crosses its 8-byte window\n",
                        (unsigned long long)s.addr, s.len);
                return 2;
            }
        } else return 2;
        if (nspec < 16) specs[nspec++] = s;
    }
    if (geteuid() != 0) {
        fprintf(stderr, "FATAL not root: ptrace of an app process requires root\n");
        return 3;
    }

    if (seize_all() != 0) return 1;   /* seize_all detached its own partials */
    wcap = slot_capacity(tids[0], NT_ARM_HW_WATCH);
    bcap = slot_capacity(tids[0], NT_ARM_HW_BREAK);
    if (wcap < 0 || bcap < 0) {
        say("FATAL GETREGSET NT_ARM_HW_* errno=%d -- not arm64, or hw debug masked", errno);
        release_all();
        return 1;
    }
    say("READY pid=%d watch_slots=%d break_slots=%d", target_pid, wcap, bcap);
    for (int i = 0; i < ntids; i++)
        if (arm_thread(tids[i]) != 0) { release_all(); return 1; }
    for (int i = 0; i < ntids; i++) pt(PTRACE_CONT, tids[i], 0, 0);

    const char *reason = "exit";
    long deadline = run_seconds ? time(NULL) + run_seconds : 0;
    for (;;) {
        if (max_hits && hits >= max_hits) { reason = "max-hits"; break; }
        if (deadline && time(NULL) >= deadline) { reason = "seconds"; break; }
        int st;
        pid_t tid = waitpid(-1, &st, __WALL | WNOHANG);
        if (tid == 0) { struct timespec ts = {0, 10 * 1000 * 1000}; nanosleep(&ts, NULL); continue; }
        if (tid < 0) {
            if (errno == EINTR) continue;
            reason = "error"; break;
        }
        if (WIFEXITED(st) || WIFSIGNALED(st)) {
            say("EXIT tid=%d code=%d", tid, WIFEXITED(st) ? WEXITSTATUS(st) : -1);
            if (tid == target_pid) { reason = "exit"; break; }
            continue;
        }
        if (!WIFSTOPPED(st)) continue;
        int sig = WSTOPSIG(st);
        unsigned event = (unsigned)st >> 16;

        /* a thread we have never seen: with TRACECLONE this is a new child --
         * arm it before it runs a single instruction if we can */
        if (!known(tid) && event == PTRACE_EVENT_STOP && sig == SIGSTOP) {
            remember(tid);
            arm_thread(tid);
            pt(PTRACE_CONT, tid, 0, 0);
            continue;
        }
        if (sig == SIGTRAP) {
            if (event != 0) {
                /* ptrace event stop (clone/exec/...): consumed by us, and it
                 * is not a signal the target generated -- restart clean */
                pt(PTRACE_CONT, tid, 0, 0);
                continue;
            }
            siginfo_t si;
            memset(&si, 0, sizeof si);
            if (pt(PTRACE_GETSIGINFO, tid, 0, (long)&si) != 0) {
                pt(PTRACE_CONT, tid, 0, 0);
                continue;
            }
            if (si.si_code == TRAP_HWBKPT) {
                struct uregs r;
                memset(&r, 0, sizeof r);
                get_regset(tid, NT_PRSTATUS, &r, sizeof r);
                int is_break = 0;
                for (int i = 0; i < nspec; i++)
                    if (specs[i].is_break && specs[i].addr == r.pc) is_break = 1;
                print_hit(tid, is_break, &r, (uint64_t)(uintptr_t)si.si_addr);
                hits++;
                if (is_break) { step_over(tid, r.pc); pt(PTRACE_CONT, tid, 0, 0); continue; }
                pt(PTRACE_CONT, tid, 0, 0);
                continue;
            }
            /* signal-delivery stop that is not our hardware trap: a genuine
             * target SIGTRAP (raise/tgkill/int3). Forward it, or the target's
             * handler never runs and the app misbehaves in ways this tool
             * would then be blamed for. */
            pt(PTRACE_CONT, tid, 0, sig);
            continue;
        }
        if ((sig == SIGSTOP || sig == SIGTSTP || sig == SIGTTIN || sig == SIGTTOU)
            && event == PTRACE_EVENT_STOP) {
            pt(PTRACE_LISTEN, tid, 0, 0);
            continue;
        }
        pt(PTRACE_CONT, tid, 0, sig);     /* the target's own signal: forward */
    }

    /* clean detach: stop everyone, clear slots, let go */
    for (int i = 0; i < ntids; i++) {
        pt(PTRACE_INTERRUPT, tids[i], 0, 0);
        int st2;
        waitpid(tids[i], &st2, __WALL);
    }
    release_all();
    say("DONE hits=%ld reason=%s", hits, reason);
    return 0;
}
'''

HELPER_README = """# hwwatch helper (generated)

Built from `hw_watch.py emit`. Two ways to build it:

NDK (host build, static):

    <ndk>/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android24-clang \\
        -O2 -static -o hwwatch hwwatch_helper.c

Termux (on-device build):

    pkg install clang
    clang -O2 -static -o hwwatch hwwatch_helper.c

Run as root, one spec per slot:

    su -c '/data/local/tmp/hwwatch <pid> w:0x7b2000123456:8:rw b:0x7b20001234c0 --seconds 20'

Checks before trusting any hit (references/hardware-watchpoints.md):
  1. READY reports watch_slots/break_slots > 0 (0 means the kernel masked hw debug).
  2. Run the control: watch a global you can touch on demand, confirm a HIT arrives.
  3. TracerPid will be non-zero while this runs -- that is the ptrace route's
     one visible artifact, and the kernel route exists for when that matters.

This helper is a template: its encodings are host-unit-tested, its device
behaviour was not measured by this repository.
"""


# ---------------------------------------------------------------------------
# subcommands
# ---------------------------------------------------------------------------
def parse_int(text):
    """Parse 0x/0o/decimal text the way the CLI accepts addresses."""
    return int(text, 0)


def cmd_plan(args):
    """plan subcommand: encode specs locally, refuse impossible ones."""
    rows = []
    err = None
    try:
        if args.addr is not None:
            wvr, wcr = encode_watch(parse_int(args.addr), args.len, args.access)
            rows.append(("watch", parse_int(args.addr), args.len, args.access, wvr, wcr))
        if args.break_addr is not None:
            bvr, bcr = encode_break(parse_int(args.break_addr))
            rows.append(("break", parse_int(args.break_addr), 4, "x", bvr, bcr))
        if not rows:
            err = "nothing to plan: give --addr or --break"
    except PlanError as exc:
        err = str(exc)
    if err:
        if args.json:
            print(json.dumps({"status": "refused", "reason": err}))
        else:
            print("refused: %s" % err)
        return 2
    if args.json:
        print(json.dumps({
            "status": "ok",
            "specs": [{"kind": k, "addr": a, "len": n, "access": ac,
                       "vr": v, "cr": c,
                       "cr_fields": explain_cr(k, c)} for (k, a, n, ac, v, c) in rows],
        }))
        return 0
    specs = []
    for (k, a, n, ac, v, c) in rows:
        print("%s addr=0x%x len=%s access=%s" % (k, a, n, ac))
        print("  %-4s = 0x%x" % ("VR", v))
        print("  %-4s = 0x%x  %s" % ("CR", c, explain_cr(k, c)))
        specs.append("w:0x%x:%d:%s" % (a, n, ac) if k == "watch" else "b:0x%x" % a)
    print("\nspec for the device: %s" % " ".join(specs))
    print("RESULT=ok")
    return 0


def explain_cr(kind, cr):
    """Decode a WCR/BCR value into its ARM DDI 0487 field meanings."""
    if kind == "watch":
        bas = (cr >> WCR_BAS_SHIFT) & 0xFF
        lsc = (cr >> 3) & 0b11
        mode = {1: "load", 2: "store", 3: "load+store"}[lsc]
        return "BAS=0x%02x LSC=%s PAC=EL0 E=1" % (bas, mode)
    return "BAS=all-ones PMC=EL0 BT=unlinked-addr E=1"


def cmd_emit(args):
    """emit subcommand: write the on-device C helper and its README."""
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    c_path = os.path.join(out, "hwwatch_helper.c")
    with open(c_path, "w", encoding="utf-8") as fh:
        fh.write(HWWATCH_C)
    with open(os.path.join(out, "README.md"), "w", encoding="utf-8") as fh:
        fh.write(HELPER_README)
    print("wrote %s" % c_path)
    print("wrote %s" % os.path.join(out, "README.md"))
    print("RESULT=ok")
    return 0


def find_ndk_clang():
    """Best-effort NDK clang discovery; env ANDROID_NDK_HOME wins."""
    env = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK")
    roots = []
    if env:
        roots.append(env)
    for base in (os.path.expanduser("~/Android/Sdk/ndk"),
                 os.path.expanduser("~/Android/sdk/ndk"),
                 "/opt/android-sdk/ndk"):
        if os.path.isdir(base):
            roots.extend(os.path.join(base, n) for n in sorted(os.listdir(base), reverse=True))
    for root in roots:
        for pre in ("toolchains/llvm/prebuilt",):
            pre_dir = os.path.join(root, pre)
            if not os.path.isdir(pre_dir):
                continue
            host = os.listdir(pre_dir)[0]
            clang = os.path.join(pre_dir, host, "bin", "aarch64-linux-android24-clang")
            if os.path.isfile(clang):
                return clang
            clang2 = os.path.join(pre_dir, host, "bin", "clang")
            if os.path.isfile(clang2):
                return clang2 + " --target=aarch64-linux-android24"
    return None


def cmd_gates(args):
    """gates subcommand: host probes plus the device-side gates."""
    rows = []
    adb = shutil.which("adb")
    rows.append(("adb on PATH", "yes" if adb else "NO -- device orchestration (run) unavailable here"))
    clang = find_ndk_clang()
    rows.append(("NDK arm64 clang",
                 clang if clang else "NO -- pass --clang, or build on device with Termux clang"))
    cc = shutil.which("gcc") or shutil.which("cc") or shutil.which("clang")
    rows.append(("host C syntax check", cc if cc else "NO -- emitted C goes unchecked on this host"))
    if args.json:
        print(json.dumps({"status": "ok" if adb else "partial",
                          "gates": [{"gate": g, "value": v} for g, v in rows]},
                         ensure_ascii=False))
        return 0 if adb else 0
    print("== hw_watch gates (host) ==")
    for g, v in rows:
        print("  %-18s %s" % (g + ":", v))
    print("\ndevice-side gates -- run these via scripts/devsh.py and read them back:")
    print("  cat /proc/<pid>/status | grep TracerPid   # must be 0: nobody else is tracing")
    print("  ls /proc/<pid>/task                       # thread count to arm")
    print("  su -c 'hwwatch <pid> w:0x..:8:rw --max-hits 1'   # READY must report slots>0")
    print("RESULT=%s" % ("ok" if adb else "partial"))
    return 0 if adb else 0


def adb_cmd(adb, serial, *argv, **kw):
    """Run one adb command; return its CompletedProcess unchecked."""
    cmd = [adb]
    if serial:
        cmd += ["-s", serial]
    return subprocess.run(cmd + list(argv), capture_output=True, text=kw.pop("text", True), **kw)


def cmd_run(args):
    """run subcommand: build, push and run the helper; stream hits."""
    adb = shutil.which("adb")
    if not adb:
        print("RESULT=blocked")
        print("no adb on PATH: the run path needs a rooted device (see gates)", file=sys.stderr)
        return 3
    # 1. locate / build the helper
    helper_dir = os.path.abspath(args.helper_dir or os.path.join(".", "hwwatch_work"))
    src = os.path.join(helper_dir, "hwwatch_helper.c")
    if not os.path.isfile(src):
        os.makedirs(helper_dir, exist_ok=True)
        with open(src, "w", encoding="utf-8") as fh:
            fh.write(HWWATCH_C)
    if args.helper:
        local_bin = args.helper
    else:
        clang_argv = (args.clang or find_ndk_clang()).split() if (args.clang or find_ndk_clang()) else None
        if args.on_device_build:
            local_bin = None  # built on the device below
        elif not clang_argv:
            print("RESULT=blocked")
            print("no arm64 compiler: pass --clang, --on-device-build, or --helper",
                  file=sys.stderr)
            return 3
        else:
            local_bin = os.path.join(helper_dir, "hwwatch")
            built = subprocess.run(clang_argv + ["-O2", "-static", "-o", local_bin, src],
                                   capture_output=True, text=True)
            if built.returncode != 0:
                print("RESULT=blocked")
                print("helper build failed:\n%s" % built.stderr, file=sys.stderr)
                return 3
    # 2. resolve the pid
    if args.pid:
        pid = args.pid
    elif args.pkg:
        out = adb_cmd(adb, args.serial, "shell", "pidof %s" % args.pkg)
        pid = (out.stdout or "").strip().split()
        if not pid:
            print("RESULT=blocked")
            print("no running process for %s (launch it first)" % args.pkg, file=sys.stderr)
            return 3
        pid = pid[0]
    else:
        print("RESULT=blocked")
        print("need --pid or --pkg", file=sys.stderr)
        return 2
    # 3. build the spec list and validate locally, before touching the device
    try:
        specs = []
        if args.addr is not None:
            encode_watch(parse_int(args.addr), args.len, args.access)
            specs.append("w:%s:%d:%s" % (args.addr, args.len, args.access))
        for b in (args.break_addr or []):
            encode_break(parse_int(b))
            specs.append("b:%s" % b)
        if not specs:
            raise PlanError("nothing to watch: give --addr or --break")
    except PlanError as exc:
        print("RESULT=refused")
        print("refused: %s" % exc, file=sys.stderr)
        return 2
    remote = "/data/local/tmp/hwwatch"

    def fail(step, detail):
        """Report RESULT=blocked with the step's output; return exit 3."""
        print("RESULT=blocked")
        print("%s failed: %s" % (step, (detail or "").strip()[:300]), file=sys.stderr)
        return 3

    if local_bin:
        r = adb_cmd(adb, args.serial, "push", local_bin, remote)
        if r.returncode != 0:
            return fail("push", r.stderr or r.stdout)
    else:
        r = adb_cmd(adb, args.serial, "push", src, remote + ".c")
        if r.returncode != 0:
            return fail("push", r.stderr or r.stdout)
        build = adb_cmd(adb, args.serial, "shell",
                        "su -c 'clang -O2 -static -o %s %s.c'" % (remote, remote))
        if build.returncode != 0:
            return fail("on-device build (Termux clang)", build.stdout or build.stderr)
    r = adb_cmd(adb, args.serial, "shell", "chmod 755 %s" % remote)
    if r.returncode != 0:
        return fail("chmod", r.stderr or r.stdout)
    argv_remote = [remote, str(pid)] + specs
    if args.max_hits:
        argv_remote += ["--max-hits", str(args.max_hits)]
    if args.seconds:
        argv_remote += ["--seconds", str(args.seconds)]
    quoted = " ".join(argv_remote)
    print("running: su -c '%s'" % quoted)
    proc = subprocess.Popen(
        [adb] + (["-s", args.serial] if args.serial else []) + ["shell", "su -c '%s'" % quoted],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    out_path = args.out or "hwwatch_hits.log"
    hits = 0
    with open(out_path, "w", encoding="utf-8") as log:
        for line in proc.stdout:
            log.write(line)
            print(line.rstrip())
            if line.startswith("HIT"):
                hits += 1
    rc = proc.wait()
    if rc != 0:
        # a denied su, a failed push/build above, or a helper FATAL exits
        # nonzero: reporting `ok-no-hits` here would call a dead capture a
        # quiet one -- exactly the false negative a control run exists to catch
        print("RESULT=blocked")
        print("capture did not complete: adb shell exited %d -- read the log above "
              "for the helper's FATAL/READY lines" % rc, file=sys.stderr)
        return 3
    print("\nhits=%d log=%s" % (hits, out_path))
    print("decode with: python hw_watch.py decode --hits %s --maps <maps.txt>" % out_path)
    print("RESULT=ok" if hits else "RESULT=ok-no-hits")
    return 0


HIT_KEYS = ("pc", "addr", "sp", "lr", "x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7")


def parse_hits(path):
    """Parse a hit log into dicts; tolerant of interleaved ARM/READY/DONE lines."""
    hits = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line.startswith("HIT"):
                continue
            row = {}
            for tok in line.split():
                if "=" in tok:
                    k, v = tok.split("=", 1)
                    if k in ("tid", "kind") or k in HIT_KEYS:
                        row[k] = v
            if row.get("pc"):
                hits.append(row)
    return hits


def parse_maps(path):
    """Accept /proc/<pid>/maps text or lib_map.py JSON; return [(start,end,path)]."""
    ranges = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        first = fh.read(4096)
        fh.seek(0)
        if first.lstrip().startswith("{"):
            doc = json.load(fh)
            rows = doc if isinstance(doc, list) else doc.get("rows", [])
            for row in rows:
                base = row.get("base")
                path = row.get("path", "?")
                if isinstance(base, int):
                    # one range per library, base..base+8MB heuristic is wrong;
                    # lib_map carries per-path base only -- flag it as such.
                    ranges.append((base, base + 0x1000, path + " (base only, no size in json)"))
            return ranges
        for line in fh:
            parts = line.split(None, 5)
            if len(parts) >= 5 and "-" in parts[0]:
                start, _, end = parts[0].partition("-")
                try:
                    path = parts[5].strip() if len(parts) > 5 else ""
                    ranges.append((int(start, 16), int(end, 16), path))
                except ValueError:
                    continue
    return ranges


def module_bases(ranges):
    """Per-path module base = that module's LOWEST mapping start.

    A library maps as several ranges (r--p, r-xp, rw-p); a PC hit in the
    r-xp range must still report its offset from the module base -- the
    number IDA/Ghidra expects -- not from the current mapping's start, which
    resets the offset at every mapping boundary."""
    bases = {}
    for start, _end, path in ranges:
        if not path:
            continue
        if path not in bases or start < bases[path]:
            bases[path] = start
    return bases


def resolve(addr, ranges, bases=None):
    """Name a runtime address as path+offset, offset taken from the module base."""
    if bases is None:
        bases = module_bases(ranges)
    for start, end, path in ranges:
        if start <= addr < end:
            base = bases.get(path, start)   # anon ranges keep their own start
            return "%s+0x%x" % (path or "anon", addr - base)
    return None


def cmd_decode(args):
    """decode subcommand: hit log plus maps -> module+offset rows."""
    hits = parse_hits(args.hits)
    ranges = parse_maps(args.maps) if args.maps else []
    bases = module_bases(ranges)
    rows = []
    unresolved = 0
    for h in hits:
        pc = parse_int(h["pc"])
        site = resolve(pc, ranges, bases) if ranges else None
        if site is None:
            unresolved += 1
        rows.append({"tid": h.get("tid"), "kind": h.get("kind"), "pc": h["pc"],
                     "site": site or "unresolved",
                     "watch_addr": h.get("addr")})
    if args.json:
        print(json.dumps({"status": "ok", "hits": len(rows),
                          "unresolved_pc": unresolved, "rows": rows}))
        return 0
    print("hits=%d  unresolved_pc=%d%s" % (len(rows), unresolved,
                                           "" if ranges else "  (no --maps: PCs stay raw)"))
    for r in rows:
        print("  tid=%s kind=%-5s pc=%s -> %s" % (r["tid"], r["kind"], r["pc"], r["site"]))
        if r["kind"] == "watch":
            print("      watched addr=%s" % r["watch_addr"])
    print("RESULT=ok")
    return 0


def main():
    """CLI entry: subcommand dispatch."""
    ap = argparse.ArgumentParser(
        description="Invisible memory watchpoints / execution breakpoints on arm64 Android "
                    "via hardware debug registers (plan, emit, gates, run, decode).",
        epilog="references/hardware-watchpoints.md owns the procedure, its stealth ledger, "
               "and the control run you owe any hit before you trust it.")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("plan", help="encode WCR/BCR for an address and validate alignment rules")
    p.add_argument("--addr", help="watchpoint data address (0x..)")
    p.add_argument("--len", type=int, default=8, choices=range(1, 9),
                   help="watch length in bytes, 1..8 (default 8)")
    p.add_argument("--access", default="rw", choices=["r", "w", "rw"],
                   help="trap on read / write / both (default rw)")
    p.add_argument("--break", dest="break_addr", metavar="ADDR",
                   help="execution breakpoint PC (4-byte aligned) instead of / in addition to a watch")
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_plan)

    p = sub.add_parser("emit", help="write the self-contained on-device C helper")
    p.add_argument("--out", required=True, help="directory to write into")
    p.set_defaults(fn=cmd_emit)

    p = sub.add_parser("gates", help="host probe: adb, arm64 compiler; plus device-side checks")
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_gates)

    p = sub.add_parser("run", help="build, push, run the helper on a rooted device, stream hits")
    p.add_argument("--pid", help="target pid (or --pkg)")
    p.add_argument("--pkg", help="resolve pid by package name")
    p.add_argument("--addr", help="watchpoint data address")
    p.add_argument("--len", type=int, default=8, choices=range(1, 9))
    p.add_argument("--access", default="rw", choices=["r", "w", "rw"])
    p.add_argument("--break", dest="break_addr", metavar="ADDR", action="append",
                   help="execution breakpoint PC (repeatable)")
    p.add_argument("--seconds", type=int, default=30, help="capture window (default 30)")
    p.add_argument("--max-hits", type=int, default=0, help="stop after N hits (0 = no cap)")
    p.add_argument("--out", default="hwwatch_hits.log", help="local hit log")
    p.add_argument("--serial", help="adb device serial")
    p.add_argument("--clang", help="explicit arm64 compiler command (quoted)")
    p.add_argument("--on-device-build", action="store_true",
                   help="build the helper with Termux clang on the device")
    p.add_argument("--helper", help="path to an already-built helper binary")
    p.add_argument("--helper-dir", help="where the generated source lives (default ./hwwatch_work)")
    p.set_defaults(fn=cmd_run)

    p = sub.add_parser("decode", help="module+offset for each HIT in a log")
    p.add_argument("--hits", required=True, help="hit log from run/")
    p.add_argument("--maps", help="/proc/<pid>/maps text or lib_map.py JSON")
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_decode)

    args = ap.parse_args()
    try:
        return args.fn(args)
    except BrokenPipeError:
        return 0


if __name__ == "__main__":
    sys.exit(main())
