#!/usr/bin/env python3
"""vm_handler_slice.py -- slice a VM/VMP interpreter trace into handler segments
and recover the guest-opcode trajectory.

What question this answers
--------------------------
A trace of a virtualized function is a trace of the interpreter: one dispatch loop,
executed once per guest instruction, with the guest's work hidden inside per-opcode
handler code. Reading that trace block-by-block tells you nothing -- but **slicing it at
dispatcher entries** turns it back into a program:

    D h_a h_b D h_c D h_a h_b D ...     (D = dispatcher, h_* = handler blocks)
    -> segment "h_a h_b" (count 2), segment "h_c" (count 1)
    -> trajectory: S(ab) S(c) S(ab) ...  == the guest bytecode, one signature per opcode

With a handler->opcode map (see below), the trajectory becomes the guest program's
opcode sequence: the disassembly of the VM you cannot read statically.

Calibration source -- an open IR-level VM, in the shape this tool expects
------------------------------------------------------------------------
This tool's segment model is calibrated against a public LLVM-IR virtualizer
(dpkg123/ollvm-aosp-toolchain, commits 63fec41f and 9b7cf75e, 2026-09 -- the `xvmp`
generic pass; source facts below are read from that patch, `inferred` for runtime
behaviour, `observed` for the source text):

- each virtualized function becomes a stub calling ``vm_exec(code, size, vm_args, num)``:
  the bytecode array plus a boxed-argument array live in the module, so the *inputs*
  (arguments, and any key material passed in) are observable at the ``vm_args`` array
  with a hardware watchpoint, before interpretation starts;
- the bytecode is a little-endian stream of one-byte opcodes with two operand spaces
  (``VT_CONST`` inline constants, ``VT_SLOT`` slot references): OP_ALLOCA 0x01,
  OP_LOAD 0x02, OP_STORE 0x03, OP_GEP 0x05, OP_CMP 0x06, OP_CAST 0x07, OP_BR 0x08,
  OP_CALL 0x09, OP_RET 0x0A, OP_ARITH 0x10 (19 arithmetic sub-ops), OP_PARAMMAP 0x11;
- pointer constants are NOT inlined: they materialize through a generated dispatch
  (HandleCallTool's const/call chain, `K_Addr/K_IntBits/K_FPBits`) at first use --
  a small set of extra blocks in the trace that is *not* the main interpreter loop,
  and a high-value observation point (the returned i64 is the constant's bits);
- an interpreter is a loop, so the dispatcher dominates the block histogram, and each
  guest opcode contributes a stable, repeating block subsequence -- which is exactly
  the signature clustering below.

Because that implementation's opcode table is public, `--opcode-map` can name every
segment; for a closed VMP the same slicing still yields the trajectory, and the map
becomes a manual labelling task over the (usually few dozen) unique segments.

How to build --opcode-map
-------------------------
Run once, read the segment table (each segment's first block = handler entry), and map
handler-entry addresses to opcodes. For the open implementation above, match the entry
block to the code generated for each `case OP_*` by disassembling the `.so` around the
dispatch switch. Map file format: ``{"<first-block-key>": "OP_LOAD", ...}``.

Inputs and behaviour
--------------------
- `--log` accepts the same grammars as `trace_diff.py` (stalker `BLK` lines, wrapped or
  not, and any address-per-line stream), by importing its parsers.
- `--dispatcher` picks the slice boundary: `auto` (the histogram's wide-margin top --
  the same dispatcher-identification rule as `references/native-dbi-and-deobfuscation.md`
  §5 From a trace to a deobfuscated function (inferred)) or an explicit block key.
- Segments are the block runs between dispatcher hits; identical runs get one signature
  (sha1[:10]). Blocks before the first / after the last dispatcher hit are reported as
  `preamble`/`tail`, never silently merged.
- Diagnostics guard the two ways this goes wrong: a dispatcher that is really a spin
  loop (every segment length 1, unique-segment ratio ~1.0), and a trace that is not a
  VM at all (no dominant block; native-dbi-and-deobfuscation.md's "trace of a VM" note).

Measured status: host-tested on synthetic interpreter traces in the calibrated shape
(tests/unit/test_vm_handler_slice.py). No real xvmp-hardened binary was ever traced by
this repository -- the calibration facts are from the public source, and the first run
against a real sample is a measurement, not a confirmation.

Examples
  python vm_handler_slice.py --log vm_trace.log
  python vm_handler_slice.py --log vm_trace.log --dispatcher liba.so+0x9f6c0 --top 15
  python vm_handler_slice.py --log vm_trace.log --opcode-map map.json
"""
import argparse
import hashlib
import json
import sys
from collections import Counter

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import trace_diff  # noqa: E402  (sibling import is the repo's convention: dexutil et al)


def pick_dispatcher(blocks):
    """Dispatcher identification, same rules as native-dbi-and-deobfuscation.md's
    trace-to-CFG step: the histogram's top, WITH many distinct predecessors (every
    handler tail jumps back to it). The margin alone is not a test: in a stack-shaped
    VM the hottest handler can execute on every guest instruction, exactly as often
    as the dispatcher, so a 4x rule refuses real interpreter traces."""
    hist = Counter(blocks)
    if not hist:
        raise trace_diff.TraceError("empty trace")
    top = hist.most_common()
    cand = top[0][0]
    preds = {blocks[i - 1] for i in range(1, len(blocks)) if blocks[i] == cand}
    if len(top) > 1 and len(preds) < 3:
        # not the only block, and entered from fewer than three predecessors:
        # a plain hot loop (or a two-cycle tie), not a dispatch loop. A pure
        # frequency margin cannot rescue it -- in a stack-shaped VM the hottest
        # handler runs exactly as often as the dispatcher, so frequency is the
        # one signal this rule refuses to trust
        return None
    return cand


def slice_segments(blocks, dispatcher):
    """-> (segments, preamble, tail): segments = list of (run, closed).
    The run after the LAST dispatcher hit is an open segment -- never closed by a
    following dispatch (exit path, or a truncated capture window) -- so it joins
    the trajectory but carries closed=False and is flagged in the output."""
    idxs = [i for i, b in enumerate(blocks) if b == dispatcher]
    if not idxs:
        raise trace_diff.TraceError("dispatcher block never executed")
    preamble = blocks[:idxs[0]]
    tail = blocks[idxs[-1] + 1:]
    segments = []
    for a, b in zip(idxs, idxs[1:]):
        run = blocks[a + 1:b]
        if run:                      # a dispatcher hit followed immediately by another
            segments.append((run, True))   # contributes nothing (miss/no-op path)
    if tail:
        segments.append((tail, False))
    return segments, preamble, tail


def signature(run):
    """A segment's run list -> its comparison signature."""
    return hashlib.sha1("\x00".join(run).encode("utf-8")).hexdigest()[:10]


def cluster(segments):
    """-> ordered trajectory of signatures + per-signature stats."""
    stats = {}
    trajectory = []
    for i, (run, closed) in enumerate(segments):
        sig = signature(run)
        trajectory.append(sig)
        if sig not in stats:
            stats[sig] = {"sig": sig, "count": 0, "first": i,
                          "blocks": run, "len": len(run), "open_tail": False}
        stats[sig]["count"] += 1
        if not closed:
            stats[sig]["open_tail"] = True
    return trajectory, stats


def diagnostics(blocks, dispatcher, segments, stats):
    """Sanity notes (spin loops, degenerate shapes)."""
    n = len(blocks)
    dcount = blocks.count(dispatcher)
    avg_len = sum(len(s) for s in segments) / len(segments) if segments else 0
    unique_ratio = len(stats) / len(segments) if segments else 1.0
    notes = []
    if avg_len <= 1.05:
        notes.append("every segment is a single block: the 'dispatcher' is probably a spin "
                     "loop or the trace granularity is wrong, not an interpreter")
    if unique_ratio > 0.9 and len(segments) > 8:
        notes.append("almost every segment is unique: not a repeating opcode-handler "
                     "structure -- either wrong dispatcher or not a VM trace")
    if dcount * 1.0 / n < 0.15:
        notes.append("dispatcher is under 15%% of blocks (%d/%d): the loop does not "
                     "dominate; re-check with trace_diff.py reduce's histogram"
                     % (dcount, n))
    return {"blocks": n, "dispatcher_hits": dcount,
            "dispatcher_share": round(dcount * 100.0 / n, 1),
            "segments": len(segments), "unique_segments": len(stats),
            "avg_segment_len": round(avg_len, 2), "notes": notes}


def apply_map(trajectory, stats, map_path):
    """Map handler keys to opcode labels; report unmapped blocks."""
    with open(map_path, "r", encoding="utf-8") as fh:
        table = json.load(fh)
    named, unmapped = [], []
    for sig in trajectory:
        run = stats[sig]["blocks"]
        key = run[0]
        if key in table:
            named.append(table[key])
        else:
            named.append("?")
            unmapped.append(key)
    return named, unmapped


def cmd_main(args):
    """Resolve input rows against the ELF; text or --json output."""
    try:
        blocks = trace_diff.read_blocks(args.log, args.format)
    except (OSError, trace_diff.TraceError) as exc:
        print("RESULT=refused")
        print(str(exc), file=sys.stderr)
        return 2
    dispatcher = args.dispatcher or pick_dispatcher(blocks)
    if dispatcher is None:
        print("RESULT=refused")
        print("no wide-margin top block: this trace does not look like an interpreter "
              "loop (see trace_diff.py reduce, and the VM-trace note in "
              "references/native-dbi-and-deobfuscation.md)", file=sys.stderr)
        return 2
    try:
        segments, preamble, tail = slice_segments(blocks, dispatcher)
    except trace_diff.TraceError as exc:
        print("RESULT=refused")
        print(str(exc), file=sys.stderr)
        return 2
    trajectory, stats = cluster(segments)
    diag = diagnostics(blocks, dispatcher, segments, stats)
    named = unmapped = None
    if args.opcode_map:
        named, unmapped = apply_map(trajectory, stats, args.opcode_map)

    if args.json:
        doc = {"status": "ok", "dispatcher": dispatcher, "diagnostics": diag,
               "preamble": preamble, "tail": tail,
               "segments": sorted(stats.values(), key=lambda s: -s["count"]),
               "trajectory": trajectory}
        if named is not None:
            doc["opcode_trajectory"] = named
            doc["unmapped_handler_blocks"] = sorted(set(unmapped))
        print(json.dumps(doc, indent=1))
        return 0

    print("dispatcher: %s  (%s%% of %d blocks, %d hits)"
          % (dispatcher, diag["dispatcher_share"], diag["blocks"],
             diag["dispatcher_hits"]))
    print("segments: %d total, %d unique, avg len %.2f%s%s"
          % (diag["segments"], diag["unique_segments"], diag["avg_segment_len"],
             ("   preamble=%d blocks" % len(preamble)) if preamble else "",
             "   [last segment UNCLOSED: exit path or truncated window]"
             if any(s.get("open_tail") for s in stats.values()) else ""))
    for note in diag["notes"]:
        print("  note: %s" % note)
    print("\nsegment table (top %d by count):" % args.top)
    for s in sorted(stats.values(), key=lambda s: (-s["count"], s["first"]))[:args.top]:
        print("  %5dx  sig=%s  len=%d  %s" % (s["count"], s["sig"], s["len"],
                                              " -> ".join(s["blocks"][:8])
                                              + (" ..." if s["len"] > 8 else "")))
    print("\ntrajectory (first %d): %s" % (args.top, " ".join(trajectory[:args.top])))
    if named is not None:
        print("opcode trajectory (first %d): %s" % (args.top, " ".join(named[:args.top])))
        if unmapped:
            print("unmapped handler blocks: %d distinct -- extend --opcode-map"
                  % len(set(unmapped)))
    print("\nRESULT=ok")
    return 0


def main():
    """CLI entry: subcommand dispatch."""
    ap = argparse.ArgumentParser(
        description="Slice a VM interpreter trace into handler segments: dispatcher "
                    "detection, segment signatures, trajectory, optional handler->opcode map.",
        epilog="Calibrated against the public xvmp IR virtualizer (vm_exec + slot machine); "
               "references/ete-diff-tracing.md owns the capture, this tool owns the "
               "de-virtualization half of the reading.")
    ap.add_argument("--log", required=True, help="interpreter trace (stalker or plain)")
    ap.add_argument("--format", choices=sorted(trace_diff.PARSERS),
                    help="force a parser (default: auto, as trace_diff.py)")
    ap.add_argument("--dispatcher", help="explicit dispatcher block key (default: auto)")
    ap.add_argument("--opcode-map", help="JSON {handler-first-block: opcode-name}")
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    try:
        return cmd_main(args)
    except BrokenPipeError:
        return 0


if __name__ == "__main__":
    sys.exit(main())
