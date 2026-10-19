#!/usr/bin/env python3
"""ete_capture.py -- arm64 hardware execution tracing (CoreSight ETE) capture, with gates first.

What this is
------------
Modern arm64 cores (ARMv8.4+ era: Cortex-A78/X1/A710/X2 and later, all ARMv9 big
cores) carry **ETE**, a trace unit that streams executed-branch/instruction
records off-core in hardware, and **TRBE**, a per-CPU buffer that collects them
*self-hosted* -- no debugger, no JTAG, no code modification, no DBI code cache.
The Linux perf subsystem exposes it as the `cs_etm` PMU: a per-thread trace
recorded with `perf record -e cs_etm//u --per-thread`, decoded on the host or
device by perf built against OpenCSD. `references/ete-diff-tracing.md` owns the
method (and the pairing with `scripts/trace_diff.py`); this script owns the
gates, the recipe and the capture.

Why the gates come first
------------------------
The ETE route is closed on most devices people actually own, and every closed
gate fails *silently* if you skip it: an old kernel reports no cs_etm PMU; a
kernel with coresight but no TRBE cannot do per-thread buffers; a perf without
OpenCSD records an aux area it cannot decode; SELinux can deny perf_event_open
even to root. Probing costs one command per gate. Guessing costs an afternoon.

    gate                     expected                        check
    kernel era               >= 5.10 for ETE/TRBE drivers    uname -r
    coresight devices        eteN / trbeN in sysfs           ls /sys/bus/coresight/devices
    cs_etm PMU               dir exists                      ls /sys/bus/event_source/devices/cs_etm
    perf with CORESIGHT      perf lists cs_etm               perf list | grep cs_etm
    permission               a control record decodes        perf record -e cs_etm//u --per-thread -- true

Measured vs unverified
----------------------
`gates` and `recipe` run on this host (gates probes adb and prints the
device-side plan; `capture` requires a device and exits 3 without one --
contract-tested in tests/cli). **No ETE trace has been captured or decoded by
this repository's passes**: no reference device here has coresight compiled in
(reference kernel 4.14.186). The command shapes follow the kernel's own
tools/perf CoreSight documentation and the OpenCSD project docs -- treat them
as the documented upstream interface, and validate with the control record
before trusting a real capture.

Examples
  python ete_capture.py gates
  python ete_capture.py gates --device            # also runs the device-side probes
  python ete_capture.py recipe
  python ete_capture.py capture --pid 12345 --seconds 10 --out perf.data
  python ete_capture.py capture --pid 12345 --control       # validation run first
"""
import argparse
import json
import re
import shlex
import shutil
import subprocess
import sys

GATES = [
    {"gate": "kernel era", "expect": ">= 5.10 (ETE/TRBE driver support in-tree)",
     "check": "uname -r", "why": "the ETE source and TRBE sink drivers landed with the "
                                 "5.10-era coresight updates; older kernels expose no ETE"},
    {"gate": "coresight devices", "expect": "ete<N> and trbe<N> under /sys/bus/coresight/devices",
     "check": "ls /sys/bus/coresight/devices", "why": "eteN = the trace unit, trbeN = the "
                                                      "self-hosted buffer; etmN/tmc_etrN without "
                                                      "trbeN means shared-sink tracing only"},
    {"gate": "cs_etm PMU", "expect": "/sys/bus/event_source/devices/cs_etm exists",
     "check": "ls /sys/bus/event_source/devices/cs_etm", "why": "perf speaks to coresight "
                                                                "through this PMU"},
    {"gate": "perf with CORESIGHT", "expect": "perf lists cs_etm, and `perf report -D` decodes "
                                              "packets (OpenCSD linked in)",
     "check": "perf list | grep cs_etm", "why": "a stock perf records an aux buffer it cannot "
                                                "decode; decode needs perf built with CORESIGHT=1"},
    {"gate": "permission", "expect": "a control record of `true` produces decodable output",
     "check": "perf record -e cs_etm//u --per-thread -o ctl.data -- true",
     "why": "SELinux can deny perf_event_open even to root; prove the whole pipe on a "
            "one-instruction process before the target"},
]

RECIPE = """Building perf with CoreSight decode (pick one):

A. On-device (Termux, no cross toolchain):
     pkg install clang make cmake git python
     git clone --depth 1 https://github.com/Linaro/OpenCSD.git && cd OpenCSD
     cmake -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j
     # then the kernel's tools/perf against it:
     git clone --depth 1 https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git
     cd linux/tools/perf && make CORESIGHT=1 \\
         EXTLIBS=-l$(pwd)/../../OpenCSD/build/lib/libopencsd \\
         O=~/perf-build WERROR=0
     ~/perf-build/perf record -e cs_etm//u --per-thread -p <PID>

B. Cross (host aarch64 toolchain): same two sources, same CORESIGHT=1, with
   CROSS_COMPILE=<prefix> and the OpenCSD built for aarch64 first. Static-link
   the result so it drops onto /data/local/tmp as one file.

C. Sanity checks once built (run these in order, read each before continuing):
     perf list | grep cs_etm            # nothing -> no PMU visibility
     perf record -e cs_etm//u --per-thread -o ctl.data -- true
     perf report -i ctl.data -D | head  # CoreSight packets -> decode works
   The control record is not optional: it is the only thing that separates a
   closed gate from a capture that looks fine and decodes to nothing.

Capture shape (TRBE per-thread):
     perf record -e cs_etm//u --per-thread -p <PID> -o perf.data -- sleep <S>
Shared sink (no TRBE; system-wide-ish, cannot --per-thread):
     perf record -e cs_etm/@tmc_etr0/u -C <CPU> -o perf.data -- sleep <S>
Decode to an address stream for scripts/trace_diff.py:
     perf script -i perf.data            # synthesized instruction/branch events
     # then feed the address-bearing lines straight to:
     python scripts/trace_diff.py diff --a a.log --b b.log
"""


def adb_base(serial):
    """Locate adb and the serial prefix; (path, prefix) or (None, [])."""
    cmd = shutil.which("adb")
    if not cmd:
        return None, None
    return cmd, (["-s", serial] if serial else [])


def adb_shell(adb, prefix, cmd, timeout=15):
    """Run one adb shell command string; return its result."""
    return subprocess.run([adb] + prefix + ["shell", cmd], capture_output=True,
                          text=True, timeout=timeout)


def probe_device(args):
    """Run every device-side gate probe; return (rows, ok_count, total)."""
    adb, prefix = adb_base(args.serial)
    if not adb:
        return None, 0, len(GATES)
    rows = []
    r = adb_shell(adb, prefix, "uname -r")
    kern = (r.stdout or "").strip()
    kern_ok = bool(re.match(r"^(\d+)\.(\d+)", kern)) and _kern_at_least(kern, 5, 10)
    rows.append({"gate": "kernel era", "probe": "uname -r", "value": kern or "?",
                 "ok": kern_ok})
    r = adb_shell(adb, prefix, "ls /sys/bus/coresight/devices 2>/dev/null")
    dev = (r.stdout or "").strip()
    has_ete = "ete" in dev
    has_trbe = "trbe" in dev
    rows.append({"gate": "coresight devices", "probe": "ls /sys/bus/coresight/devices",
                 "value": (dev or "<none>")[:200], "ok": has_ete and has_trbe,
                 "note": "ete=%s trbe=%s (trbe absent -> shared-sink only)"
                         % (has_ete, has_trbe)})
    r = adb_shell(adb, prefix, "ls /sys/bus/event_source/devices/cs_etm 2>/dev/null")
    rows.append({"gate": "cs_etm PMU", "probe": "ls .../cs_etm",
                 "value": "present" if (r.stdout or "").strip() else "absent",
                 "ok": r.returncode == 0 and bool((r.stdout or "").strip())})
    r = adb_shell(adb, prefix, "su -c 'perf list' 2>/dev/null | grep -c cs_etm")
    n = (r.stdout or "").strip().splitlines()[-1] if (r.stdout or "").strip() else "0"
    rows.append({"gate": "perf with CORESIGHT", "probe": "perf list | grep cs_etm",
                 "value": "%s cs_etm entries" % n, "ok": n not in ("", "0")})
    rows.append({"gate": "permission", "probe": "control record (see recipe)",
                 "value": "not probed by gates; run capture --control",
                 "ok": False})
    ok = sum(1 for row in rows if row["ok"])
    return rows, ok, len(GATES)


def _kern_at_least(kern, maj, mnr):
    """True when the kernel version is at least major.minor."""
    m = re.match(r"^(\d+)\.(\d+)", kern)
    if not m:
        return False
    a, b = int(m.group(1)), int(m.group(2))
    return (a, b) >= (maj, mnr)


def cmd_gates(args):
    """gates subcommand: host probes plus the device-side gates."""
    adb = shutil.which("adb")
    if args.json:
        doc = {"status": "ok", "gates": GATES, "adb": bool(adb)}
        if args.device and adb:
            rows, ok, total = probe_device(args)
            doc["device_rows"] = rows
            doc["device_ok"] = ok
            doc["device_total"] = total
            doc["status"] = "ok" if ok == total else "partial"
        print(json.dumps(doc, indent=1))
        return 0
    print("== ETE capture gates ==")
    for g in GATES:
        print("\n  %s\n    expect: %s\n    check : %s\n    why   : %s"
              % (g["gate"], g["expect"], g["check"], g["why"]))
    print("\nhost: adb %s" % ("present" if adb else "ABSENT -- capture cannot run here"))
    device_verdict = "ok"
    if args.device and adb:
        rows, ok, total = probe_device(args)
        print("\ndevice probes (%d/%d closed):" % (ok, total))
        for row in rows:
            mark = "ok  " if row["ok"] else "OPEN"
            print("  [%s] %-22s %s" % (mark, row["gate"], row["value"]))
        if ok < total:
            device_verdict = "partial"
            print("\nAn OPEN gate is a route decision, not a failure -- record it per")
            print("references/kernel-and-environment-hardening.md and do not retry mid-task.")
    elif args.device:
        print("\n--device asked for device probes but adb is absent")
    print("\nRESULT=%s" % ("ok" if (adb and device_verdict == "ok") else "partial"))
    return 0 if adb else 0


def cmd_recipe(args):
    """recipe subcommand: build perf with CoreSight/OpenCSD."""
    print(RECIPE)
    print("RESULT=ok")
    return 0


def control_record_command(out_name):
    """The one-instruction control record: `perf record` of `true` alone.

    The whole command travels inside one `su -c` string, so quoting is
    two-layered: quote the filename inside the inner command, then quote the
    complete inner command for `su -c` (a quote spliced between the literal
    single quotes would run in the outer device shell).
    """
    inner = ("cd /data/local/tmp && perf record -e cs_etm//u --per-thread "
             "-o %s -- true" % shlex.quote(out_name))
    return "su -c %s 2>&1" % shlex.quote(inner)


def control_decode_command(out_name):
    """The control's decode gate: `perf script` on the control recording."""
    inner = "cd /data/local/tmp && perf script -i %s" % shlex.quote(out_name)
    return "su -c %s 2>&1" % shlex.quote(inner)


def perf_record_command(args):
    """The capture command line handed to `adb shell`.

    Two rules that are easy to get wrong and load-bearing here: `-o` must
    stay BEFORE the `--` workload separator (perf hands everything after
    `--` to the target command, so a trailing `-o` never reaches perf), and
    every interpolated value is shell-quoted because the whole command
    travels inside one `su -c '...'` string -- an unquoted value containing
    a quote would run in the outer device shell. `--cmd` is left unquoted
    on purpose: the workload argument list is meant to be word-split by the
    device shell.
    """
    q = shlex.quote
    target = "-p %s" % q(str(args.pid)) if args.pid else "-- %s" % args.cmd
    remote = "/data/local/tmp/%s" % (args.out.split("/")[-1] or "perf.data")
    inner = ("cd /data/local/tmp && timeout %d perf record -e %s --per-thread "
             "-o %s %s" % (args.seconds, q(args.event), q(remote), target))
    return "su -c %s 2>&1" % q(inner)


def cmd_capture(args):
    """capture subcommand: control record, record, pull."""
    if args.seconds < 1:
        # `timeout 0` disables the timer (or kills instantly, by flavour):
        # either way the --pid route has no capture window and the CLI would
        # wait on a record that never ends. Refused before any device check:
        # argument validity must not depend on adb being present.
        print("RESULT=refused")
        print("--seconds must be a positive capture window, got %d" % args.seconds,
              file=sys.stderr)
        return 2
    adb = shutil.which("adb")
    if not adb:
        print("RESULT=blocked")
        print("no adb on PATH: capture needs a rooted device with the ETE gates closed "
              "(run `gates --device` first)", file=sys.stderr)
        return 3
    prefix = ["-s", args.serial] if args.serial else []
    if args.control:
        out = args.out.replace(".data", "") + ".ctl.data" if args.out.endswith(".data") \
            else args.out + ".ctl.data"
        ctl = out.split("/")[-1]
        r = adb_shell(adb, prefix, control_record_command(ctl), timeout=60)
        print(r.stdout or "")
        if (r.returncode != 0
                or ("cs_etm" in (r.stdout or "") and "failed" in (r.stdout or "").lower())):
            print("RESULT=blocked")
            print("control record failed: the ETE pipe is not usable on this device as-is")
            return 3
        # A record exiting 0 only proves perf was not refused -- the documented
        # --control guarantee (references/ete-diff-tracing.md: "the control has
        # produced packets") is that the record DECODES. Hold the capture to
        # the same bar the reference checklist applies to a human.
        dec = adb_shell(adb, prefix, control_decode_command(ctl), timeout=60)
        print(dec.stdout or "")
        dec_lines = [ln for ln in (dec.stdout or "").splitlines()
                     if ln.strip() and not ln.startswith("#")]
        if dec.returncode != 0 or not dec_lines:
            print("RESULT=blocked")
            print("control record did not decode (perf script exit %d, %d event lines): "
                  "a capture taken now would be indistinguishable from an empty one, "
                  "and an empty trace is a capture failure, not a result"
                  % (dec.returncode, len(dec_lines)), file=sys.stderr)
            return 3
        print("control record done: %s (decoded %d event lines)" % (out, len(dec_lines)))
    if not args.pid and not args.cmd:
        print("RESULT=refused")
        print("capture needs --pid or --cmd", file=sys.stderr)
        return 2
    perf_cmd = perf_record_command(args)
    remote = "/data/local/tmp/%s" % (args.out.split("/")[-1] or "perf.data")
    # a perf.data left by an earlier run would let the pull below succeed on
    # stale data; remove it first (best effort -- the exit-status check below
    # is what fails the run when the pipe is unusable)
    adb_shell(adb, prefix,
              "su -c %s 2>&1" % shlex.quote("rm -f %s" % shlex.quote(remote)),
              timeout=15)
    print("running: %s" % perf_cmd)
    proc = subprocess.Popen([adb] + prefix + ["shell", perf_cmd],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for line in proc.stdout:
        print(line.rstrip())
    rc = proc.wait()
    if rc not in (0, 124):   # 124 is `timeout`'s end-of-window kill: the normal end
        print("RESULT=blocked")
        print("capture did not complete: adb shell exited %d (su denied, perf refused, "
              "or the workload never started)" % rc, file=sys.stderr)
        return 3
    local = args.out
    pull = subprocess.run([adb] + prefix + ["pull", remote, local],
                          capture_output=True, text=True)
    if pull.returncode != 0:
        print("RESULT=blocked")
        print("pull failed: %s" % (pull.stderr or "").strip())
        return 3
    print("pulled %s" % local)
    print("decode next: perf script -i %s   (feed the address lines to trace_diff.py)"
          % local)
    print("RESULT=ok")
    return 0


def main():
    """CLI entry: subcommand dispatch."""
    ap = argparse.ArgumentParser(
        description="CoreSight ETE hardware trace capture on arm64 Android: gates, "
                    "perf-with-CORESIGHT recipe, per-thread record wrapper.",
        epilog="references/ete-diff-tracing.md owns the method and the differential "
               "that turns two captures into an answer.")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("gates", help="the five gates, why each exists, and how to probe it")
    p.add_argument("--device", action="store_true",
                   help="also run the device-side probes over adb (needs a device)")
    p.add_argument("--serial", help="adb device serial")
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_gates)

    p = sub.add_parser("recipe", help="print how to build perf with CoreSIGHT/OpenCSD decode")
    p.set_defaults(fn=cmd_recipe)

    p = sub.add_parser("capture", help="record a per-thread cs_etm trace on the device")
    p.add_argument("--pid", help="trace this pid (--per-thread, TRBE path)")
    p.add_argument("--cmd", help="spawn and trace this command instead of --pid")
    p.add_argument("--seconds", type=int, default=10, help="record window (default 10)")
    p.add_argument("--event", default="cs_etm//u",
                   help="perf event spec (default cs_etm//u; shared sink: cs_etm/@tmc_etr0/u)")
    p.add_argument("--out", default="perf.data", help="local output file after pull")
    p.add_argument("--control", action="store_true",
                   help="run the one-instruction control record first and refuse on failure")
    p.add_argument("--serial", help="adb device serial")
    p.set_defaults(fn=cmd_capture)

    args = ap.parse_args()
    try:
        return args.fn(args)
    except BrokenPipeError:
        return 0


if __name__ == "__main__":
    sys.exit(main())
