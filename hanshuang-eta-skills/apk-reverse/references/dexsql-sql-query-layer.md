# dexsql — the whole dex as SQL tables, and the APK's resources beside it

Load this when: you are about to answer a question about a dex by grepping an export, or you already
use `droidasc`/`rasc` for `findrefs` and want the same answers as a relational query — or when you
want the artifact's manifest and resources in the same query surface as its code.

**In one line:** a third indexer at the same stage as ASC, with the same class-definition answers
(measured identical on four archives), slower than `rasc` on a plain class list and faster than
`droidasc`, and one thing neither of the others has: the entire dex as SQL tables plus an MCP server.

## What it is

`LSPosed/dexsql` is a single self-contained binary (`dexsql-windows-amd64.exe`, 21.4 MB, v0.1.0). No
Python, no JVM, no SDK, no first-run index build. It parses the dex once into a relational model and
answers queries against it:

```bash
dexsql tables  --input app.apk                      # every table and column type
dexsql query   --input app.apk --sql "SELECT count(*) AS n FROM methods"
dexsql query   --input app.apk --interactive        # keep the dataset loaded, read SQL from stdin
dexsql decode  --input app.apk --class 'Lcom/x/Y;'  # smali for a class or one method
dexsql prepare-cache --input app.apk --output cache # persistent cache.dxc2 for repeated queries
dexsql query-cache --cache-file cache/cache.dxc2 --sql "SELECT ..."
dexsql mcp                                          # MCP server over stdio
```

Acquire it the same way as `ddc` — it is a release download, not a build:

```bash
# https://github.com/LSPosed/dexsql-release/releases  -> dexsql-<platform>-<arch>
```

**It is a registered capability.** `doctor.py` reports `dex_query_sql` as `OK` when the binary is on
PATH, on `APKREV_TOOLS`, or at the location this kit's own install step uses (`tools/_work/bin/`,
`DEXSQL`), and `BLOCKED` with the release URL as the next action when it is not.

## The three indexers, and which one to reach for

| | `rasc` | `droidasc` | `dexsql` |
|---|---|---|---|
| Answers class/reference questions | yes | yes | yes |
| Plain class list, 34.8 MB app | **0.055 s** | 0.200 s | 0.136 s |
| Reference query | `findrefs` (substring) | `findrefs` (substring) | SQL `WHERE value LIKE` |
| Read one class | `getclass` (Java) | `getclass` (Java) | `decode` (**smali**, not Java) |
| Relational model (callers, field accesses, numeric constants, debug lines, annotations, hiddenapi) | no | no | **yes** |
| APK manifest / resources / `resources.arsc` as queries | manifest only | manifest only | **yes, as SQL + MCP tools** |
| MCP server | no | no | **yes (13 tools)** |

**The division of labour is now three-way:** `rasc` locates fastest, `droidasc` is the Python
cross-check, and `dexsql` answers the questions the other two cannot express — *which* methods call a
callee, which method holds a numeric constant above a threshold, what the debug line table says, which
annotations resolve on which owner. Reach for `dexsql` when the question is a **join or an
aggregate**, and for `rasc` when the question is a **class list**.

**`decode` is not a decompiler.** It emits smali, and that is a feature: it is the bytecode-level
answer, which is what `dex-patching.md` tells you to decide with when a decompiler's Java looks
surprising.

## The table model — what a query can ask

~60 tables, grouped by what they answer (the full list is `dexsql tables`):

| Group | Tables worth knowing |
|---|---|
| Identity / structure | `dex_files` (per-dex header fields, `checksum`, `signature_hex`, counts), `classes`, `class_defs`, `class_interfaces`, `fields`, `methods`, `protos`, `type_lists` |
| Code | `code_items` (`registers_size`, `ins_size`, `tries_size`), `method_opcodes`, `try_items`, `catch_handlers` |
| **Cross-reference** | `method_calls` (caller → callee), `method_field_accesses`, `method_strings` (method → literal), `method_used_types`, `method_numbers` (numeric constants), `method_using_numbers`, `method_refs` |
| Debug info | `debug_info`, `debug_positions` (address → line), `debug_locals`, `debug_parameters`, `debug_opcodes` |
| Annotations | `annotations`, `annotation_elements`, `annotation_values`, `annotation_elements_resolved` (owner + annotation + element flattened) |
| Runtime surface | `hiddenapi_flags`, `call_sites`, `method_handles` |

The cross-reference group is the part that replaces an hour of grepping: `method_calls`,
`method_field_accesses`, `method_strings`, `method_used_types` and `method_numbers` are all
pre-joined views carrying `method`, `method_class` and `method_name` on the calling side.

## Measured here

Environment: Windows 10.0.26100, `dexsql 0.1.0` (Windows amd64 release asset, sha256
`f354ebcf715de9a66ef8ebe08b2b36807ab58e25cd5a1c0812e497fc1fcfc448`), PowerShell 7. Four archives:
a 1.4 MB MASTG challenge (1,396 classes), a 4.3 MB hardened app (647), a 38.7 MB hardened app (4) and
a 34.8 MB app (30,768 classes / 268,446 methods).

**Class-definition sets — identical to all three other tools, on every archive.** This is the check
that decides whether a new indexer may be trusted for location questions at all, and it is the same
comparison `rasc_build.py --verify` performs between `rasc` and `droidasc`:

| Archive | dexsql | rasc | droidasc | ddc | verdict |
|---|---|---|---|---|---|
| 1.4 MB MASTG challenge | 1,396 | 1,396 | 1,396 | 1,396 | **identical** |
| 4.3 MB hardened | 647 | 647 | 647 | 647 | **identical** |
| 38.7 MB hardened | 4 | 4 | 4 | 4 | **identical** |
| 34.8 MB app | 30,768 | 30,768 | 30,768 | 30,768 | **identical** |

Zero differences in either direction on all four — including the two archives where the count is
small because the payload is encrypted inside a shell, which is the target and not the tool.

**Full class listing, best of 3, stdout to a file (the same shape for every tool):**

| Archive | dexsql | rasc | droidasc | ddc |
|---|---|---|---|---|
| 1.4 MB / 1,396 classes | 0.035 s | 0.028 s | 0.149 s | 0.109 s |
| 4.3 MB / 647 classes | 0.031 s | 0.025 s | 0.139 s | 0.099 s |
| 38.7 MB / 4 classes | 0.040 s | 0.021 s | 0.156 s | 0.092 s |
| 34.8 MB / 30,768 classes | 0.136 s | **0.055 s** | 0.200 s | 0.156 s |

So on a real app `dexsql` is **1.5× faster than `droidasc`** and **2.5× slower than `rasc`**; the
`rasc` and `droidasc` columns reproduce this repository's earlier figures (0.048 / 0.190 s on the
same 34.8 MB archive), which is what makes the row comparable rather than merely new. A count-only
query on the same archive is 0.056 s — the listing itself is most of the difference.

**String cross-reference agrees with ASC.** On the 1.4 MB challenge, `SELECT DISTINCT method_class,
method_name FROM method_strings WHERE value LIKE '%http%'` returns exactly the three sites
`rasc findrefs … string http` and `droidasc findrefs … string http` return, and `ddc findrefs` agrees.
**The matching semantics differ and the difference is a trap:** `findrefs` matches a *substring*,
while SQL needs `LIKE '%…%'` — `value = 'http'` legitimately returns zero rows, because no method
holds a literal that *is* `http`.

**`decode` agrees with `ddc disasm`.** `dexsql decode --method
'Lsg/vantagepoint/uncrackable3/MainActivity;->verifyLibs()V'` emits the same instruction sequence, in
the same order, as `ddc disasm … MainActivity.verify` — checked line by line on that method.

**Persistent cache: a real feature with a small measured payoff here.** `prepare-cache` on the
34.8 MB archive takes 0.196 s and writes a 34.5 MB `cache.dxc2`. Repeating a query that needs the
full dataset — `method_calls` count, and a top-20 `GROUP BY` aggregate over it — went from 0.121 s to
0.098 s and from 0.470 s to 0.458 s. That is **under 20%, not an order of magnitude**, because the
parse-and-build path is already 0.12 s on this archive. Treat the cache as a way to hold one dataset
across many queries in a long session, not as a speed switch; on small archives it is a net loss
(cache read 0.071 s against a 0.042 s direct query on the 38.7 MB sample).

**Read-only is enforced, and the error surface is uniform.** Missing class → `Error: not_found:
selected file, method, or class not found`, exit 1. Missing input, a non-dex input, an unknown column,
an unknown relation and a `DELETE` all exit 1 with a named cause; the write is refused.

## Recipes worth keeping

```sql
-- who calls what, ranked (the question `findrefs` cannot express)
SELECT callee_class, callee_name, count(*) AS n FROM method_calls
GROUP BY 1,2 ORDER BY n DESC LIMIT 20;

-- every literal a method holds, instead of one needle at a time
SELECT method_class, method_name, value FROM method_strings
WHERE method_class LIKE 'Lcom/target/%' ORDER BY method_class;

-- numeric constants above a threshold: config values, protocol magic, crypto parameters
SELECT method_class, method_name, number_type, long_value FROM method_numbers
WHERE long_value > 1000000 ORDER BY long_value DESC LIMIT 50;

-- methods with no code (native, abstract) versus the ones that carry it
SELECT class_descriptor, name, method_kind, opcode_count FROM methods
WHERE opcode_count IS NULL OR opcode_count = 0;

-- which owners carry a given annotation
SELECT owner, annotation, count(*) AS n FROM annotation_elements_resolved
GROUP BY 1,2 ORDER BY n DESC LIMIT 20;

-- debug line table size: a cheap signal for "was this dex built with debug info"
SELECT count(*) AS positions FROM debug_positions;
```

`--sql` repeats, so several statements run against one loaded dataset; `--cache name=SELECT …` defines
a temporary table for the invocation; `--interactive` keeps the dataset loaded for a session. A
`method_idx` is DEX-local, so on a multi-dex APK carry `file_id` (from `dex_files`) into the join —
`-i` accepts several inputs and they share one dataset.

**The MCP surface is the same model for an agent.** `dexsql mcp` speaks JSON-RPC over stdio and
publishes 13 tools — `open_dataset`, `list_tables`, `describe_table`, `query`, `decode`,
`get_manifest`, `get_manifest_components`, `list_resource_files`, `read_resource`, `query_resources`,
`list_datasets`, `close_dataset`, plus an embedded documentation catalog on `resources/list`. The
resource tools read `resources.arsc` and archive entries **without extracting them to disk**, and
every list-shaped result is paged by `next_offset`. Datasets are opened by path and are capped
(`--max-datasets`, `--max-input-bytes`, `--timeout-ms`), so a client that forgets `close_dataset`
degrades rather than grows.

## Boundaries — what this does not settle

- **A hit is a reference, not a call, and SQL does not change that.** `method_strings` proves a method
  holds a literal; whether that method runs on the path you care about is still a disassembly or
  runtime question (`dynamic-frida.md`).
- **`decode` gives smali, not Java.** When the question is Java-level, `ddc getclass` or `jadx` is
  still the tool; `dexsql` is where you *locate* and where you check bytecode.
- **One version, one platform family was measured.** v0.1.0 on Windows amd64. Linux/macOS assets exist
  in the same release but were not exercised here (`unverified`), and no other dexsql version was
  tested at all.
- **The cache is not a speed switch** — see the measurement above; the honest claim is "hold a dataset
  across queries", not "queries get fast".
- **`--trusted-input` skips input-consistency checks.** It exists for controlled build pipelines; do
  not reach for it to make a suspicious file load.
- **A packed target stays packed.** On both hardened archives `dexsql` returns the same 4 and 647
  classes the other three tools return: the business dex is not in the file, and no query language
  changes that. Take the question to the unpacking routes.
- **`quickened`/odex opcodes are unsupported** by `decode` (`odex` input is out of scope).
- **This is not a patching tool.** Nothing here writes a dex; `dex-patching.md` and
  `byte-level-patching.md` own that, and `dexsql` is where you find the site to patch.

## Provenance

`https://github.com/LSPosed/dexsql-release` (release `v0.1.0`, published 2026-10-15; the repository
is the release host — it carries binaries, not source), retrieved and measured 2026-10-16. Nothing is
vendored into this repository: the binary lives in the gitignored `tools/` work area, and the
measurements above name the version, the platform and the hashes they came from.
