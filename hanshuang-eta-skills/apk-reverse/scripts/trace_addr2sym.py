#!/usr/bin/env python3
"""trace_addr2sym.py -- turn a stream of runtime addresses into symbols and static offsets.

What question this answers
--------------------------
Every hardware-layer tool in this skill ends with a list of addresses: `hw_watch.py` hits,
ETE/SPE block streams, `trace_diff.py` divergences, BRBE branch records. An address is not a
finding until it names **which library, which function, which static offset** -- the last part
being what you paste into IDA/Ghidra, where ASLR never existed. This script closes that loop
with a zero-dependency ELF64 reader (no pyelftools, no readelf): PT_LOAD segments for
runtime-address -> vaddr -> file-offset conversion, `.dynsym` first then `.symtab`, and a
stripped-library fallback that still gives section + static offsets.

PIE and the two offset spaces, stated once
------------------------------------------
- **runtime address** = what the trace/hit carries (ASLR-affected)
- **vaddr** = runtime - load_bias, where load_bias is derived from the module's
  lowest mapping aligned against its PT_LOAD (bias = map_start - (map_foff -
  p_offset) - p_vaddr): it equals the mapping base exactly when the lowest
  PT_LOAD has p_vaddr 0, the normal PIE case (`lib_map.py`/maps give you this)
- **file offset** = what the disassembler wants; vaddr -> file goes through PT_LOAD again,
  and it is **not** always the identity: p_vaddr != p_offset happens in hand-linked and
  self-decrypting payloads, and silently wrong offsets are exactly the failure this tool
  exists to prevent.
- `mod+0xoff` keys (stalker logs, trace_diff output) are runtime - base == vaddr under the
  same assumption; use `--trace --mod <name> --elf <path>` for that input shape.

Measured vs unverified: the ELF walker is host-tested against a real cross-compiled
aarch64 shared library (gcc, `.dynsym` + `.symtab`) and against a hand-built minimal ELF
(tests/unit/test_trace_addr2sym.py) -- `observed`. Nothing here touches a device.

Examples
  python trace_addr2sym.py --hits hwwatch_hits.log --maps maps.txt --elf liba.so
  python trace_addr2sym.py --trace traceA.log --mod liba.so --elf liba.so
  python trace_addr2sym.py --elf liba.so --addrs 0x7b20001234c0 --base 0x7b2000000000
  python trace_addr2sym.py --elf liba.so --offs 0x1234c0        # module offsets directly
"""
import argparse
import bisect
import json
import os
import struct
import sys

PT_LOAD = 1
SHT_SYMTAB = 2
SHT_DYNSYM = 11
STT_FUNC = 2
STT_OBJECT = 1


class ElfError(ValueError):
    pass


class Elf64:
    """Zero-dependency ELF64 little-endian reader: segments, sections, symbols."""

    def __init__(self, path):
        """Read the file; build segments, sections and the symbol table."""
        with open(path, "rb") as fh:
            self.data = fh.read()
        if self.data[:4] != b"\x7fELF":
            raise ElfError("%s is not an ELF" % path)
        if self.data[4] != 2 or self.data[5] != 1:
            raise ElfError("%s is not ELF64 little-endian" % path)
        (self.e_type, self.e_machine) = struct.unpack_from("<HH", self.data, 16)
        (e_phoff,) = struct.unpack_from("<Q", self.data, 0x20)
        (e_shoff,) = struct.unpack_from("<Q", self.data, 0x28)
        (e_phentsize, e_phnum) = struct.unpack_from("<HH", self.data, 0x36)
        (e_shentsize, e_shnum, e_shstrndx) = struct.unpack_from("<HHH", self.data, 0x3A)
        self.segments = []
        # per-segment loaded spans (memsz included: .bss objects live past
        # filesz). The gate must test each segment: real shared objects leave
        # unmapped gaps between PT_LOADs, and a whole-span check would admit a
        # PC sitting in one of those gaps
        self.mem_ranges = []
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type, _flags, p_offset, p_vaddr = struct.unpack_from("<IIQQ", self.data, off)
            p_filesz, p_memsz = struct.unpack_from("<QQ", self.data, off + 0x20)
            if p_type == PT_LOAD:
                self.segments.append((p_vaddr, p_vaddr + p_filesz, p_offset))
                self.mem_ranges.append((p_vaddr, p_vaddr + p_memsz))
        self.sections = []
        if e_shoff:
            shstr_off = self._sh_off(e_shoff, e_shentsize, e_shstrndx, self.data)
            for i in range(e_shnum):
                off = e_shoff + i * e_shentsize
                (sh_name, sh_type) = struct.unpack_from("<II", self.data, off)
                (sh_offset, sh_size) = struct.unpack_from("<QQ", self.data, off + 0x18)
                (sh_link, _sh_info, _sh_align, sh_entsize) = struct.unpack_from(
                    "<IIQQ", self.data, off + 0x28)
                name = self._cstr(shstr_off + sh_name) if shstr_off else ""
                self.sections.append({
                    "name": name, "type": sh_type, "offset": sh_offset,
                    "size": sh_size, "link": sh_link, "entsize": sh_entsize,
                })
        self.symbols = self._read_symbols()
        # function/object symbols with a value, best-candidate-first ordering later
        self.funcs = sorted(
            [(s["value"], s["size"], s["name"]) for s in self.symbols
             if s["value"] and s["type"] in (STT_FUNC, STT_OBJECT)],
            key=lambda t: t[0])
        # cached for resolve(): trace rows can name thousands of PCs and a
        # per-call list build is O(symbols) each time
        self.symbol_addresses = [f[0] for f in self.funcs]

    @staticmethod
    def _sh_off(e_shoff, e_shentsize, idx, data):
        """File offset of the shstrtab section header, or 0."""
        if idx == 0:
            return 0
        (sh_offset,) = struct.unpack_from("<Q", data, e_shoff + idx * e_shentsize + 0x18)
        return sh_offset

    def _cstr(self, off):
        """NUL-terminated string at a file offset."""
        end = self.data.find(b"\x00", off)
        return self.data[off:end].decode("utf-8", "replace") if end > off else ""

    def _read_symbols(self):
        """Flatten .symtab/.dynsym into symbol rows."""
        out = []
        for sec in self.sections:
            if sec["type"] not in (SHT_SYMTAB, SHT_DYNSYM) or not sec["entsize"]:
                continue
            strtab = None
            if 0 < sec["link"] < len(self.sections):
                strtab = self.sections[sec["link"]]["offset"]
            count = sec["size"] // sec["entsize"] if sec["entsize"] else 0
            for i in range(count):
                off = sec["offset"] + i * sec["entsize"]
                (st_name, st_info) = struct.unpack_from("<IB", self.data, off)
                (st_value, st_size) = struct.unpack_from("<QQ", self.data, off + 8)
                name = self._cstr(strtab + st_name) if strtab is not None else ""
                if name:
                    out.append({"name": name, "value": st_value, "size": st_size,
                                "type": st_info & 0xF})
        return out

    # -- conversions ---------------------------------------------------------
    def contains_vaddr(self, vaddr):
        """True when vaddr lies inside the loaded span of some PT_LOAD."""
        return any(lo <= vaddr < hi for lo, hi in self.mem_ranges)

    def vaddr_to_file(self, vaddr):
        """vaddr -> file offset through a PT_LOAD, or None."""
        for p_vaddr, end, p_offset in self.segments:
            if p_vaddr <= vaddr < end:
                return p_offset + (vaddr - p_vaddr)
        return None

    def file_to_vaddr(self, foff):
        """file offset -> vaddr through a PT_LOAD, or None."""
        for p_vaddr, end, p_offset in self.segments:
            if p_offset <= foff < p_offset + (end - p_vaddr):
                return p_vaddr + (foff - p_offset)
        return None

    def section_of(self, vaddr):
        """(section name, offset inside it) for a vaddr."""
        for s in self.sections:
            # section addr is not stored in this minimal reader; fall back by file offset
            foff = self.vaddr_to_file(vaddr)
            if foff is not None and s["offset"] <= foff < s["offset"] + s["size"] and s["name"]:
                return s["name"], foff - s["offset"]
        return None, None

    def resolve(self, vaddr):
        """-> (symbol, sym_off) or (None, None). Nearest symbol at-or-below
        vaddr, addr2line-style: a PC between two sized symbols reports the
        lower one, because a trace PC in a gap (alignment padding, cold tails,
        stripped locals) still owes you its neighbourhood. Callers that must
        not attribute foreign PCs should gate on contains_vaddr() first."""
        if not self.funcs:
            return None, None
        i = bisect.bisect_right(self.symbol_addresses, vaddr) - 1
        if i < 0:
            return None, None
        value, _size, name = self.funcs[i]
        return name, vaddr - value


def load_bias_from_maps(maps_path, module_hint, elf):
    """Load bias for the module matched by full pathname or basename ONLY.

    A substring match would also admit `liba.so`'s neighbours
    (`liba.so.v2`, `xliba.so`) and take their mapping. And the bias is not
    simply the lowest mapping start -- that holds only when the lowest
    PT_LOAD has p_vaddr 0. Align the mapping's file offset with a PT_LOAD
    segment instead: bias = map_start - (map_foff - p_offset) - p_vaddr.
    """
    best = None   # (lowest mapping start, its file offset)
    with open(maps_path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            parts = line.split(None, 5)
            if len(parts) >= 6:
                path = parts[5].strip()
                if module_hint == path or module_hint == os.path.basename(path):
                    start = int(parts[0].split("-")[0], 16)
                    foff = int(parts[2], 16)
                    if best is None or start < best[0]:
                        best = (start, foff)
    if best is None:
        return None
    start, foff = best
    for p_vaddr, end, p_offset in elf.segments:
        if p_offset <= foff < p_offset + (end - p_vaddr):
            return start - (foff - p_offset) - p_vaddr
    return None   # maps and ELF disagree: the file offset is not one of its segments


def read_hw_hits(path):
    """hw_watch.py HIT log -> ('hits', pc) rows."""
    rows = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith("HIT"):
                for tok in line.split():
                    if tok.startswith("pc="):
                        rows.append(("hits", tok[3:]))
    return rows


def read_addr_lines(path):
    """One address per line -> ('addrs', tok) rows."""
    rows = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            toks = line.split()
            if not toks:
                continue
            tok = None
            for t in reversed(toks):
                s = t[2:] if t.lower().startswith("0x") else t
                try:
                    int(s, 16)
                    tok = t
                    break
                except ValueError:
                    continue
            if tok:
                rows.append(("addrs", tok))
    return rows


def read_off_lines(path):
    """One module offset per line -> ('offs', tok) rows."""
    rows = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            for tok in line.split():
                s = tok[2:] if tok.lower().startswith("0x") else tok
                try:
                    int(s, 16)
                except ValueError:
                    continue
                rows.append(("offs", tok))
                break
    return rows


def read_modoff_trace(path, mod):
    """mod+0xoff keys from a stalker/trace log -- only keys for `mod`."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import trace_diff
    rows = []
    try:
        blocks = trace_diff.read_blocks(path)
    except Exception as exc:  # noqa: BLE001 -- surfaced as a usage error below
        raise ElfError(str(exc))
    for key in blocks:
        if "+" in key:
            name, _, off = key.rpartition("+")
            if name == mod or os.path.basename(name) == mod:
                rows.append(("offs", off))
    return rows


def describe(elf, vaddr):
    """Name one vaddr, refusing PCs that are outside this ELF's span: pinning
    another module's PC on this ELF's last symbol is the misleading output,
    not a helpful guess."""
    if not elf.contains_vaddr(vaddr):
        return ("<outside-elf> vaddr 0x%x (outside every PT_LOAD span of this ELF "
                "-- another module's PC, or a wrong base?)" % vaddr)
    sym, sym_off = elf.resolve(vaddr)
    foff = elf.vaddr_to_file(vaddr)
    sec, sec_off = elf.section_of(vaddr)
    if sym:
        return "%s+0x%x  [vaddr 0x%x file 0x%s%s]" % (
            sym, sym_off, vaddr,
            ("%x" % foff) if foff is not None else "?",
            (" %s+0x%x" % (sec, sec_off)) if sec else "")
    if sec:
        return "<stripped> %s+0x%x  [vaddr 0x%x file 0x%s]" % (
            sec, sec_off, vaddr, ("%x" % foff) if foff is not None else "?")
    return "<no mapping> vaddr 0x%x (outside every PT_LOAD -- wrong base?)" % vaddr


def cmd_main(args):
    """Resolve input rows against the ELF; text or --json output."""
    rows = []
    if args.hits:
        rows = read_hw_hits(args.hits)
    elif args.trace:
        rows = read_modoff_trace(args.trace, args.mod)
    elif args.addrs:
        rows = [("addrs", a) for a in args.addrs]
    elif args.offs:
        rows = [("offs", a) for a in args.offs]
    elif args.addr_file:
        rows = read_addr_lines(args.addr_file)
    elif args.off_file:
        rows = read_off_lines(args.off_file)
    if not rows:
        print("RESULT=refused")
        print("no addresses: give --hits/--trace/--addrs/--offs/--addr-file/--off-file",
              file=sys.stderr)
        return 2
    try:
        elf = Elf64(args.elf)
    except (OSError, ElfError) as exc:
        print("RESULT=refused")
        print(str(exc), file=sys.stderr)
        return 2
    base = args.base
    if base is not None:
        base = int(base, 0)
    if base is None and args.maps:
        hint = args.mod or os.path.basename(args.elf)
        base = load_bias_from_maps(args.maps, hint, elf)
        if base is None:
            print("RESULT=refused")
            print("no load bias for %r in %s (no exact mapping matched, or its file "
                  "offset does not align with %s's PT_LOADs)"
                  % (hint, args.maps, os.path.basename(args.elf)), file=sys.stderr)
            return 2
    if args.trace and base is None:
        base = 0  # mod+off keys already are module-relative: treat them as vaddr
    out = []
    for kind, tok in rows:
        val = int(tok, 16) if not tok.lower().startswith("0x") else int(tok, 16)
        if kind in ("offs",):
            vaddr = val if not args.offs_are_file else elf.file_to_vaddr(val)
            if vaddr is None:
                out.append({"input": tok, "error": "file offset outside PT_LOAD"})
                continue
        else:
            if base is None:
                print("RESULT=refused")
                print("absolute addresses need --base or --maps (ASLR: the trace address "
                      "means nothing without the module's load base)", file=sys.stderr)
                return 2
            vaddr = val - base
        out.append({"input": tok, "vaddr": vaddr, "where": describe(elf, vaddr)})
    if args.json:
        # a pure JSON document on stdout -- the status field carries what
        # RESULT= would say, and appending the marker would break every
        # json.loads(stdout) consumer (the convention check_commands.py set)
        print(json.dumps({"status": "ok", "elf": args.elf,
                          "base": base, "resolved": out}, indent=1))
    else:
        print("elf=%s base=%s%s" % (args.elf, hex(base) if base is not None else "?",
                                    " (from --offs: offsets treated as %s)"
                                    % ("file offsets" if args.offs_are_file else "vaddrs")
                                    if args.offs else ""))
        for row in out:
            if "error" in row:
                print("  %-16s ERROR %s" % (row["input"], row["error"]))
            else:
                print("  %-18s -> %s" % (row["input"], row["where"]))
        print("RESULT=ok")
    return 0


def main():
    """CLI entry: subcommand dispatch."""
    ap = argparse.ArgumentParser(
        description="Runtime addresses -> symbol + static offset, zero-dependency ELF64: "
                    "PT_LOAD conversion, .dynsym/.symtab, stripped fallback.",
        epilog="references/hardware-sampling-and-crash.md owns the crash/address loop this "
               "closes; the offset-space rules are in this header because a silently wrong "
               "offset is the failure mode.")
    ap.add_argument("--elf", required=True, help="the .so/.o to resolve against")
    ap.add_argument("--hits", help="hw_watch.py HIT log (absolute PCs)")
    ap.add_argument("--trace", help="stalker/trace log; use keys for --mod only")
    ap.add_argument("--addrs", nargs="*", help="absolute runtime addresses (0x..)")
    ap.add_argument("--offs", nargs="*", help="module offsets (vaddr space, or --offs-are-file)")
    ap.add_argument("--addr-file", help="file of one address per line")
    ap.add_argument("--off-file", help="file of one module offset per line")
    ap.add_argument("--mod", help="module name for --trace keys / --maps matching")
    ap.add_argument("--maps", help="/proc/<pid>/maps to find the load base from")
    ap.add_argument("--base", help="explicit load base (overrides --maps)")
    ap.add_argument("--offs-are-file", action="store_true",
                    help="--offs carries file offsets, not vaddrs")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    try:
        return cmd_main(args)
    except BrokenPipeError:
        return 0


if __name__ == "__main__":
    sys.exit(main())
