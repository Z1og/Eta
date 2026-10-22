# Eta knowledge and WebDAV memory sync

## Overview

Eta keeps four independent local data classes:

| Layer | Android private file | Scope |
| --- | --- | --- |
| Core memory | `files/memory/MEMORY.md` | User-editable stable facts, preferences and projects |
| Task experience | `files/memory/task_experiences.json` | Bounded tool-name/error-code evidence; no raw user texts |
| Knowledge | `files/memory/knowledge.json` | Revisioned facts, procedures and reference Markdown documents |
| Structured Retain | `files/memory/retained_memories.json` | Model-extracted atomic memories, layers, provenance, entity keys, and unverified typed relation proposals |

The knowledge layer is available through `knowledge_search`, `knowledge_get`,
`knowledge_save` and `knowledge_delete`. Search uses a local, Chinese-aware
lexical ranker (word terms, Han character n-grams and BM25-style scoring);
it is *not* a vector or embedding index. Documents have stable IDs, revision
hashes, type (`fact`, `procedure`, `reference`), source and timestamps.
Knowledge is treated as reference data, not trusted instructions.

### Structured Retain (opt-in)

Enable **Memory -> Smart Retain** separately from the basic tool-experience switch.
For each completed normal chat run, Eta may make one additional, tool-free call
to the **currently configured model** to extract up to three durable memories
from the latest user message. This costs additional model tokens and delays
the run's final completion; it is **off by default**.

- It does not persist full conversations or raw tool arguments/results. A
  candidate must include a *literal evidence quote* from the user message.
- Known sensitive formats (passwords, tokens, OTPs, phone numbers and related
  labels) are conservatively blocked, but the detector is not infallible.
  Review and delete anything you do not want remembered.
- Semantic facts/preferences, explicit reusable procedures and
  user-stated project events have distinct layers, stable node IDs, scope,
  confidence, entity strings, source-run hashes and temporal validity.
- The model is allowed to propose typed links to existing memory IDs:
  `SAME_TOPIC`, `SUPPORTS`, `CONTRADICTS`, `PREREQUISITE`,
  `APPLIES_TO`. Graph learning can run a dedicated, tool-free classifier over
  the newly retained nodes and existing candidates. It assigns relationship
  types, confidence and short explanations without confirming its own work.
- Repeated assertions are deduplicated, explicit revisions can invalidate
  prior claims while keeping history, and deletions/clears are synchronized.
- **Default semantic retrieval uses the configured chat model** to re-rank
  a bounded set of local candidates from core `MEMORY.md`, knowledge notes
  and retained nodes. No separate embedding model is required. The app
  splits `MEMORY.md` into titled, bounded retrieval chunks without changing
  the original file. Cross-language semantic selection works even when
  no Retain nodes exist.
- Real embedding cosine similarity from a compatible HTTPS
  `POST /embeddings` endpoint is an optional advanced supplement, not a
  prerequisite. Vectors remain cached locally by model and content revision.
- Graph recall starts with at most 4 fused seed nodes, explores **confirmed**
  edges up to 2 hops, and allows at most 2 **unreviewed proposals** as strictly
  labeled one-hop discovery hints. Rejected edges and invalidated nodes never
  participate. CONTRADICTS is flagged as a potential conflict, not support.
- Open **Memory -> Structured memory -> Node details -> Relation** to inspect
  the explanation and accept/reject individual edges.

For design details, limitations and later upgrades, see
[RETAIN_GRAPH_BLUEPRINT.md](RETAIN_GRAPH_BLUEPRINT.md).

### Enable semantic + graph memory (optional)

1. Enable core memory and **Smart Retain**. Enable **Graph recall** to allow
   model classification of new memories and bounded graph expansion.
2. Enable **Chat model semantic retrieval**. Eta makes one additional
   tool-free model call to re-rank up to 24 memory candidates and displays
   the last retrieval mode, candidate count and result count in Settings.
   If the model call fails, it falls back to local lexical retrieval.
3. **Optional:** If your selected chat provider supports an OpenAI-compatible
   HTTPS `/embeddings` endpoint, enable **Advanced: Embeddings supplement**
   and specify the distinct **embedding model ID**. The default shown is
   `text-embedding-3-small`; availability depends on your provider.
   A chat-completions model does **not** automatically support embeddings.
   Tap **Test Embeddings API** to make a real test request and display vector
   dimensions or a clear error; this works even with zero Retain nodes.
4. Tap **Build / repair local vector index** for existing Retain nodes only.
   When there are zero Retain nodes, the button is disabled, but chat-model
   search still works over `MEMORY.md` and knowledge documents.
   The tool embeds memory statements in batches (up to 24 each). On subsequent
   conversations Eta sends the query plus only new/missing memory text;
   it retains vectors in the app-private `memory/semantic_vectors_v1.json`.
5. Tap **Model classify existing relations** to evaluate at most 12 recent
   nodes. It may make multiple extra model calls; review its proposals in the
   memory list. Automatic classification of newly saved nodes runs only
   when Smart Retain and graph recall are both enabled.

All optional controls are **off by default**. Chat semantic retrieval sends
the current query and a bounded set of memory snippets to the existing
configured chat model. Embeddings additionally send the current query
and uncached memory text to the selected compatible **model provider**,
not to WebDAV; model charges, network latency and the provider's data policy
apply. If embeddings fail, chat semantic retrieval remains available. WebDAV
does not sync the local vector cache; devices may use different models.
Changing the embedding model automatically invalidates the old vectors.

On Android, open **Memory -> Knowledge** to search document content, add or
edit a note, or import a `.md`, `.markdown` or `.txt` document through the
system file picker. A single imported note is limited to 12,000 characters;
PDF/Word parsing and automatic document chunking are not implemented yet.

References worth studying for future evolution:
- Hindsight: retain/recall/reflect and observation over time:
  https://github.com/vectorize-io/hindsight
- Supermemory: user profile plus document retrieval:
  https://github.com/supermemoryai/supermemory
- Graphiti: temporal validity and provenance for evolving facts:
  https://github.com/getzep/graphiti
- Mem0: extraction, deduplication and memory lifecycle:
  https://github.com/mem0ai/mem0

Eta does not bundle these services or require an external memory database.

## Configure Android sync

1. Open **Eta -> Memory -> WebDAV encrypted sync**.
2. Enter an existing **HTTPS WebDAV collection URL**, e.g.
   `https://dav.example.net/remote.php/dav/files/alice/Eta/`.
   Eta creates `eta-sync-v1/state.bin` under that existing folder.
3. Enter the WebDAV account username/password if needed.
4. Enter a separate **sync encryption passphrase** (12+ characters).
   Enter the exact same passphrase on every device. Losing it makes remote
   content unrecoverable. Do not put it in the URL.
5. Save the connection, then tap **Sync now**. Repeat on the other device.
6. Optionally enable **Background auto sync**. Android JobScheduler targets
   a period of approximately 15 minutes, but Doze/battery/network rules may
   delay actual delivery. Use Sync now for a timely exchange.

No OpenList-specific implementation or server-side module is needed. Servers
must provide strong ETags for existing documents and support conditional
`PUT` with `If-Match` / `If-None-Match`, plus `MKCOL` and `GET`.
A missing ETag or incompatible server fails closed without overwriting data.

## Windows / macOS / Linux companion

The optional script `scripts/eta-webdav-sync.py` reads and writes the exact
same encrypted sync format. Python 3.10+ and the `cryptography` package are
required:

```powershell
python -m pip install cryptography
$env:ETA_WEBDAV_URL = 'https://dav.example.net/dav/Eta/'
$env:ETA_WEBDAV_USER = 'alice'
$env:ETA_WEBDAV_PASSWORD = 'your-webdav-password'
$env:ETA_SYNC_PASSPHRASE = 'your-separate-long-sync-passphrase'
python scripts/eta-webdav-sync.py sync --dir .\eta-data
```

The local `eta-data` folder contains `MEMORY.md`, `knowledge.json`,
`task_experiences.json`, `retained_memories.json` and a hidden
`.eta-sync-base.json` merge baseline.
These files are **plaintext on the local computer**. Protect the directory.
Do not commit credentials, exported personal memories, or sync contents into
the source repository.

Add a Markdown note to the local knowledge collection:

```powershell
python scripts/eta-webdav-sync.py add --dir .\eta-data --title "My procedure" --file .\note.md --kind procedure --source "user note" --tag android
python scripts/eta-webdav-sync.py sync --dir .\eta-data
```

For edits, inspect `knowledge.json` and modify the document you intend.
The script assigns a new revision if a previously synced document has changed.
For routine knowledge changes, the Android UI or Agent tools are easier.

## Data safety and merge behavior

- The WebDAV payload is **AES-256-GCM**, with an independent random salt and
  nonce for each upload. Key derivation is PBKDF2-HMAC-SHA256 (180,000 rounds).
  The remote server receives ciphertext, not plaintext memory or knowledge.
- WebDAV account passwords and encryption passphrases are stored on Android
  using the existing Android Keystore encrypted secret store.
- On each sync, Eta fetches the remote ETag, runs a three-way merge against
  its last successful local baseline, and conditionally uploads. HTTP 412
  triggers a fresh read/merge, never an unconditional overwrite.
- Independent knowledge documents merge by stable ID and revision. Divergent
  edits become a deterministic `(sync conflict copy)` rather than dropping
  either active version. Deletes are tombstones and are retained for merge.
  If deletion races an edit, the original ID stays deleted and the edited
  text becomes an independent, reviewable conflict copy.
- `MEMORY.md` is a freeform Markdown document. If both sides edit it
  independently from the same baseline, Eta **stops syncing** instead of
  choosing a winner. On Android the remote text is preserved at
  `files/memory/conflicts/remote-memory-<hash>.md`. On PC it is written to
  `remote-memory-conflict.md`. Review and manually reconcile the two texts,
  save your merged local `MEMORY.md`, and explicitly confirm the locally
  reviewed version in Android Memory settings. The dialog displays a remote
  preview and can copy the entire remote text into the clipboard. On PC,
  run `python scripts/eta-webdav-sync.py sync --dir ./eta-data --resolve-memory-conflict`
  **only after** reviewing the conflict file and saving your merged local
  Markdown. Both clients re-fetch the remote ETag and reject an unreviewed
  remote memory change. The baseline is intentionally not advanced on conflict.
- Task experiences merge by event ID. A clear operation records a timestamp
  cutoff so older experiences cannot be reintroduced by a stale peer.
- Retained memories merge by stable node ID with tombstones and validity metadata.
  Typed relations now contain a proposed/confirmed/rejected status, confidence,
  model explanation and review timestamp. An explicit user review takes
  precedence over any newer **model proposal**; among user reviews, the newer
  decision prevails.
- The remote payload format is now **schema v3**. Updated Android and Python
  clients read schema v1/v2 and upgrade it on write. Older v1/v2 clients
  deliberately refuse v3 and must be upgraded before syncing further.
- Sync covers the four local stores above, **not** raw conversations, Root access
  logs, provider API keys, MCP credentials, photos or audio.
- The Android App settings and the local encrypted secret store are
  **not themselves synced**. Each device configures its own WebDAV login
  and encryption passphrase.

## Current limits

- Core memory: 1 MiB.
- Knowledge: 512 documents, 12,000 characters per document, 4 MiB local file.
- Task experiences: 256 records; recording uses a controlled task vocabulary.
- Structured Retain: 400 atomic memories; each <= 280 characters plus evidence,
  entities, timestamps and up to 12 typed relations with review statuses.
- Local semantic index: <= 16 MiB, at most 400 vectors with <= 3072 dimensions.
  Embedding data is rebuildable and not synced via WebDAV.
- Entire WebDAV sync payload: 7 MiB plaintext max.
- The auto experience feature is separate, opt-in and off by default.
- No dedicated vector database, on-device embedding model, global entity
  canonicalization, or graph relations to imported PDF/Word references yet.
  The current bounded graph covers **Retain atomic memory nodes** only.
  Automatic LLM reflection/mental models are not implemented.

## Useful tests

```powershell
.\gradlew.bat :app:compileDebugKotlin --offline
.\gradlew.bat :app:testDebugUnitTest --tests io.github.mangi.eta.data.repository.EtaKnowledgeSyncTest --offline
python -m py_compile scripts/eta-webdav-sync.py
```

The Android tests cover versioned knowledge, memory three-way merge,
experience clear tombstones, encryption tamper/wrong-passphrase handling,
HTTPS URL validation, concurrent edit/delete preservation, and a
Python -> Android AES-GCM interoperability vector. The companion script has
its own regression suite: `python scripts/test_eta_webdav_sync.py`.
A live multi-device WebDAV end-to-end test is still required before treating
background sync as production-proven.
