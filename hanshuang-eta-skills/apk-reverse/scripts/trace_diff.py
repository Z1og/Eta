#!/usr/bin/env python3
"""trace_diff.py -- differential execution-trace reduction: two runs, one answer.

What question this answers
--------------------------
Not "what executed" but "**where did run A stop behaving like run B**". You run
the same operation twice with exactly one input changed -- the password's last
character, license valid versus invalid, key 0x00... versus key 0xff... -- and
diff the two execution traces. Every common block is machinery; every divergent
block is a decision that *your one-bit input difference* reached. Against OLLVM
control-flow flattening the divergences name the dispatcher states actually
taken; against a VM/VMP interpreter they name the handler sequence for the two
inputs; against whitebox-style crypto they name the key-dependent branch set.
This is the "differential" half of `references/ete-diff-tracing.md`, and it is
producer-agnostic by design.

What it accepts (auto-detected, or forced with --format)
-------------------------------------------------------
stalker      `stalker_trace.js` logs: `BLK <seq> <mod>+0x<off>` block events
             (BB/CALL/DONE lines ignored), including logs wrapped by
             `run_probe.py` as `[host ts] [dev ts] TRACE BLK ...`.
plain        any text where each line carries an address -- the last
             whitespace token that parses as hex (`0x7f...`, `7f...`, and
             `mod+0xoff` keys kept verbatim so modules stay comparable).
             Decoded CoreSight/ETE `perf script` output fits this shape, as do
             hand-scrapped disassembly walks.

What it produces
----------------
reduce   the run-length form of one trace: ordered `(block, count)` runs plus
         the execution histogram -- the same reductions `stalker_report.py`
         prints, in a form the differ consumes.
diff     an alignment of the two run-length sequences (difflib on the block
         keys): identical prefix/suffix lengths, **first divergence**, each
         divergent region with per-side counts, and the blocks unique to each
         side ranked by count. The unique-to-B list is where "what does the
         valid path do that the invalid path does not" is answered.

Discipline the tool does not enforce for you
--------------------------------------------
Same build and same address space (module+offset keys, never raw pointers,
unless ASLR is off); same input length; same capture window; and a *pair* whose
only difference is the input bit you chose. A diff across two builds or two
code versions is noise, and the tool cannot tell -- garbage in, confident
nonsense out.

Measured status: the parsers and the reducer/differ are host-tested on
synthetic flattened-dispatcher fixtures (tests/unit/test_trace_diff.py). No
ETE-produced trace has been fed to it by this repository's passes -- see
references/ete-diff-tracing.md for the producer side and its device gates.

Examples
  python trace_diff.py reduce --log traceA.log
  python trace_diff.py diff --a traceA.log --b traceB.log --top 15
  python trace_diff.py diff --a wrong.log --b right.log --json
"""
import argparse
import difflib
import json
import re
import sys
from collections import Counter

# A block key is a hex address: `0x`-prefixed (unambiguous), or a bare run
# of at least 8 hex digits that is NOT pure decimal -- an all-digit run is
# indistinguishable from a date, a PID range or a count, and a log holding
# only those must stay refused (an empty trace is a capture failure, not a
# result). Shorter bare runs would swallow PIDs and timestamp fragments.
HEX_TOK = re.compile(r"^(?:0x[0-9a-fA-F]+|(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{8,})$")
MOD_OFF = re.compile(r"^[A-Za-z0-9_.+-]+\+0x[0-9a-fA-F]+$")
BLK_LINE = re.compile(r"(?:^|\s)BLK\s+\d+\s+(\S+)(?:\s|$)")


class TraceError(ValueError):
    pass


# ---------------------------------------------------------------------------
# parsing
# ---------------------------------------------------------------------------
def parse_line_stalker(line):
    """One BLK line -> its block key, or None."""
    m = BLK_LINE.search(line)
    return m.group(1) if m else None


def parse_line_plain(line):
    """One plain trace line -> its block key, or None."""
    toks = line.split()
    for tok in reversed(toks):
        if MOD_OFF.match(tok):
            return tok
        if HEX_TOK.match(tok):
            return tok.lower()
    return None


PARSERS = {"stalker": parse_line_stalker, "plain": parse_line_plain}


def read_blocks(path, fmt=None):
    """Ordered block keys from a trace log. Empty input raises: a diff against
    nothing is a plumbing failure, not an analysis result (the zero-event
    lesson from native-dbi-and-deobfuscation.md applies here too)."""
    blocks = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if fmt:
                key = PARSERS[fmt](line)
            else:
                key = parse_line_stalker(line) or parse_line_plain(line)
            if key:
                blocks.append(key)
    if not blocks:
        raise TraceError(
            "no block keys parsed from %s -- an empty trace is a capture "
            "failure, not a result; see the zero-event diagnostics in "
            "references/native-dbi-and-deobfuscation.md" % path)
    return blocks


def run_length(blocks):
    """[(key, run_len)] preserving order -- D D D R1 becomes (D,3),(R1,1)."""
    runs = []
    for b in blocks:
        if runs and runs[-1][0] == b:
            runs[-1][1] += 1
        else:
            runs.append([b, 1])
    return [(k, n) for k, n in runs]


# ---------------------------------------------------------------------------
# reduction report
# ---------------------------------------------------------------------------
def reduce_trace(blocks):
    """Histogram and run-length summary of one block list."""
    hist = Counter(blocks)
    return {
        "events": len(blocks),
        "distinct_blocks": len(hist),
        "runs": len(run_length(blocks)),
        "top": hist.most_common(),
        "sequence": run_length(blocks),
    }


# ---------------------------------------------------------------------------
# differential
# ---------------------------------------------------------------------------
def diff_traces(a_blocks, b_blocks):
    """Structural diff: first divergence, regions, uniques."""
    a_runs, b_runs = run_length(a_blocks), run_length(b_blocks)
    a_keys = [k for k, _ in a_runs]
    b_keys = [k for k, _ in b_runs]
    sm = difflib.SequenceMatcher(a=a_keys, b=b_keys, autojunk=False)

    identical = sum(n for i, j, n in sm.get_matching_blocks())
    regions = []
    first_divergence = None
    a_hist, b_hist = Counter(a_blocks), Counter(b_blocks)

    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            continue
        if first_divergence is None:
            first_divergence = {
                "run_index_a": i1, "run_index_b": j1,
                "event_index_a": sum(n for _, n in a_runs[:i1]),
                "event_index_b": sum(n for _, n in b_runs[:j1]),
                "last_common_block": a_runs[i1 - 1][0] if i1 > 0 else None,
            }
        regions.append({
            "tag": tag,
            "a_runs": a_runs[i1:i2],
            "b_runs": b_runs[j1:j2],
        })

    only_a = sorted(((k, v) for k, v in (a_hist - b_hist).items()),
                    key=lambda kv: -kv[1])
    only_b = sorted(((k, v) for k, v in (b_hist - a_hist).items()),
                    key=lambda kv: -kv[1])
    common = a_hist & b_hist

    return {
        "a_events": len(a_blocks), "b_events": len(b_blocks),
        "a_distinct": len(a_hist), "b_distinct": len(b_hist),
        "shared_blocks": sum(common.values()),
        "identical_run_span": identical,
        "divergent_regions": len(regions),
        "first_divergence": first_divergence,
        "regions": regions,
        "unique_to_a": only_a,
        "unique_to_b": only_b,
    }


def render_diff(d, top=10, context=3):
    """Human-readable rendering of a diff_traces document."""
    out = []
    out.append("run A: %d events, %d distinct blocks" % (d["a_events"], d["a_distinct"]))
    out.append("run B: %d events, %d distinct blocks" % (d["b_events"], d["b_distinct"]))
    out.append("shared block executions: %d   divergent regions: %d"
               % (d["shared_blocks"], d["divergent_regions"]))
    fd = d["first_divergence"]
    if fd is None:
        out.append("\nNO DIVERGENCE: the two runs executed the same block sequence.")
        out.append("Either the input difference never reached control flow (pure data),")
        out.append("or the two logs came from the same run. Check the producer.")
    else:
        out.append("")
        out.append("first divergence: after run #%d (A) / #%d (B), "
                   "%d/%d events in, last common block %s"
                   % (fd["run_index_a"], fd["run_index_b"],
                      fd["event_index_a"], fd["event_index_b"],
                      fd["last_common_block"] or "<start>"))
    for idx, r in enumerate(d["regions"][:top], 1):
        out.append("")
        out.append("region %d (%s)" % (idx, r["tag"]))
        out.append("  A: " + _fmt_runs(r["a_runs"]))
        out.append("  B: " + _fmt_runs(r["b_runs"]))
    if d["unique_to_a"] or d["unique_to_b"]:
        out.append("")
        out.append("blocks unique to A (input-dependent, A-side), top %d:" % top)
        for k, n in d["unique_to_a"][:top]:
            out.append("  %6d  %s" % (n, k))
        out.append("blocks unique to B (input-dependent, B-side), top %d:" % top)
        for k, n in d["unique_to_b"][:top]:
            out.append("  %6d  %s" % (n, k))
    return "\n".join(out)


def _fmt_runs(runs):
    """Format a run list for the region renderer."""
    if not runs:
        return "<nothing>"
    return " -> ".join("%s%s" % (k, ("*%d" % n) if n > 1 else "") for k, n in runs[:12]) + \
        (" ... (%d runs)" % len(runs) if len(runs) > 12 else "")


# ---------------------------------------------------------------------------
# subcommands
# ---------------------------------------------------------------------------
def cmd_reduce(args):
    """reduce subcommand."""
    try:
        blocks = read_blocks(args.log, args.format)
    except (OSError, TraceError) as exc:
        print("RESULT=refused")
        print(str(exc), file=sys.stderr)
        return 2
    rep = reduce_trace(blocks)
    if args.json:
        print(json.dumps({"status": "ok", "log": args.log,
                          "events": rep["events"],
                          "distinct_blocks": rep["distinct_blocks"],
                          "runs": rep["runs"],
                          "top": rep["top"][:args.top]}, indent=1))
        return 0
    print("%s: %d events, %d distinct blocks, %d runs"
          % (args.log, rep["events"], rep["distinct_blocks"], rep["runs"]))
    print("\ntop %d blocks (dispatcher candidate = the wide-margin top):" % args.top)
    for k, n in rep["top"][:args.top]:
        print("  %6d  %s" % (n, k))
    print("RESULT=ok")
    return 0


def cmd_diff(args):
    """diff subcommand."""
    try:
        a = read_blocks(args.a, args.format)
        b = read_blocks(args.b, args.format)
    except (OSError, TraceError) as exc:
        print("RESULT=refused")
        print(str(exc), file=sys.stderr)
        return 2
    d = diff_traces(a, b)
    if args.json:
        doc = dict(d)
        doc["status"] = "ok"
        doc["regions"] = d["regions"][:args.top]
        doc["unique_to_a"] = d["unique_to_a"][:args.top]
        doc["unique_to_b"] = d["unique_to_b"][:args.top]
        print(json.dumps(doc, indent=1))
        return 0
    print(render_diff(d, top=args.top))
    print("RESULT=ok")
    return 0


def main():
    """CLI entry: subcommand dispatch."""
    ap = argparse.ArgumentParser(
        description="Differential reduction of two execution traces (stalker or plain "
                    "address logs): first divergence, divergent regions, unique blocks.",
        epilog="references/ete-diff-tracing.md owns the method: same build, same "
               "window, one changed input bit -- and what the divergences mean "
               "against OLLVM flattening and VMP handler loops.")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("reduce", help="run-length + histogram of one trace")
    p.add_argument("--log", required=True, help="trace log (stalker or plain)")
    p.add_argument("--format", choices=sorted(PARSERS), help="force a parser (default: auto)")
    p.add_argument("--top", type=int, default=20)
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_reduce)

    p = sub.add_parser("diff", help="align two traces and report the divergences")
    p.add_argument("--a", required=True, help="trace log A (e.g. the failing/invalid run)")
    p.add_argument("--b", required=True, help="trace log B (e.g. the passing/valid run)")
    p.add_argument("--format", choices=sorted(PARSERS), help="force a parser (default: auto)")
    p.add_argument("--top", type=int, default=10, help="regions / unique blocks to show")
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_diff)

    args = ap.parse_args()
    try:
        return args.fn(args)
    except BrokenPipeError:
        return 0


if __name__ == "__main__":
    sys.exit(main())
