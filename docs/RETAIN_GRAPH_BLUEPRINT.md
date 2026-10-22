# Eta layered semantic and graph memory: implementation and roadmap

This document separates the features now implemented in Eta from future work.

## Why this approach

Hindsight separates world facts, experiences, observations and mental models; Retain
extracts facts and relationships and Recall uses semantic, keyword, graph and time
retrieval. Graphiti represents dated entities and sourced temporal relationships
with an ontology. Mem0 distinguishes extraction, deduplication and memory
lifecycle. Eta borrows their *design constraints*, not their server/runtime
dependencies:

- https://github.com/vectorize-io/hindsight
- https://github.com/getzep/graphiti
- https://github.com/mem0ai/mem0

## Current code (Retain + semantic graph recall)

- `AgentRetainExtractor`: optional separate model call on a normal completed user
  turn, up to 3 candidates, no tools, no raw transcript storage.
- `RetainedMemoryStore`: 400 bounded atomic nodes with stable IDs, source hash,
  literal evidence quote, provenance, scope, timestamps, occurrence count,
  invalidation and delete tombstones, type and entity strings.
- `RetainedRelation`: typed links, model confidence and justification,
  `proposed` / `confirmed` / `rejected` review status and decision timestamp.
- `AgentMemoryRelationClassifier`: separate model-driven node-to-node relation
  labeling after Retain (or a user-triggered review of 12 recent nodes);
  only existing IDs and same-scope links are allowed.
- `AgentEmbeddingClient` and `EtaMemoryVectorIndex`: standard HTTPS
  OpenAI-compatible embeddings and a local-only, content/model-versioned
  vector cache. Optional, off by default; failures fall back to lexical.
- `AgentSemanticMemorySearch`: query and uncached memory batch embedding,
  cosine ranking, and an explicit local-index repair action.
- `AgentGraphMemoryRecall`: BM25/semantic RRF seed selection and bounded graph
  expansion. Confirmed edges can travel two hops, proposals one hop only,
  rejected/invalidated nodes are excluded, and contradictions are labeled.
- `EtaSyncPayload`: WebDAV encrypted schema **v3** includes retained nodes and
  graph review state, with read migration from v1/v2. Older clients fail
  closed on v3 and must be upgraded.

## Memory layers

| Tier | Role | Storage status | Typical retention |
| --- | --- | --- | --- |
| L0 working | This run's screen state, plan and tool outputs | Existing Agent context/checkpoint | Run scoped, not synced |
| L1 episodic | Events, attempted procedures, observed tool errors and task state | Existing task-experience store plus Retain episodic nodes | Long-lived but bounded; avoid treating attempt as success |
| L2 semantic | Explicit user facts/preferences and sourced reference knowledge | Retain semantic and knowledge.json | Until superseded or deleted |
| L2 procedural | Reusable explicit instructions or verified operation patterns | Retain procedural and knowledge.json | Until revised; verify against current device |
| L3 synthesized | Evidence-backed observations and mental models | **Planned; not implemented** | Revise as evidence accumulates |

Each memory belongs to a *scope* (global or app-specific in v1). Future revisions
can add project/agent scopes, but avoid mixing unrelated knowledge banks without
explicit scope settings. Facts are assertions attributed to users, not proven truths.

## Retrieval pipeline (implemented, with stated limits)

1. Optionally encode the current task or question with a separate, explicit
   embedding model hosted by the currently configured compatible Provider.
   Maintain embeddings in an Android-local index with dimension, model
   fingerprint and content revision for re-indexing. Do not sync
   embedding vectors between devices.
2. Retrieve up to 4 seed **memory nodes** by cosine and BM25 keyword relevance.
   Fuse the two lists with reciprocal rank fusion; filter scope and invalidated
   or deleted nodes *before* ranking.
3. Use seed IDs to traverse **confirmed** graph edges up to 2 hops and
   **proposed** edges at most 1 hop (at most 2 proposals across the result).
   All branches are bounded (<= 4 neighbors per expansion).
   Relation-specific weights distinguish SAME_TOPIC, SUPPORTS, PREREQUISITE,
   APPLIES_TO; CONTRADICTS should flag a conflict, not boost a claim as support.
   Downweight unreviewed edges and more distant hops.
4. Deduplicate, re-rank with seed score and edge confidence under a bounded
   text budget. Return memory IDs, evidence and relation path labels so the
   Agent can distinguish directly retrieved facts from inferred connections.
5. Agent uses references as untrusted context only, never commands. Verify
   the current screen before actions and never silently trust historical
   coordinates or an earlier task's success status.

## Model-driven graph construction (partially implemented)

A low-cost extractor is run when a new node is retained or revised:

A. **Entity tagging and canonicalization**. The model suggests entities and
aliases that *must* be grounded in the evidence snippet or an existing trusted
entity, e.g. `Eta`, `微信`, `安卓 Root`; separate facts from aliases.

B. **Candidate retrieval**. Currently the classifier uses a bounded lexical
shortlist and recency; using semantic vectors to choose candidate relation
targets is future work. Only Retain nodes (not knowledge documents) are linked.

C. **Relation proposal**. Implemented output:
`{source_id, target_id, type, confidence, reason}`. The app attaches
`status:"proposed"` and `updated_at` after validating both IDs and scopes.
Links never become confirmed solely because a model reports confidence 0.99.

D. **Validation**. Existing checks reject cross-scope, self, orphan, low-confidence
and unbounded model links. Graph traversal bounds cycles and ignores invalidated
nodes. Further factual entailment/provenance audits remain future work.
Only explicit user confirmation upgrades a proposal to `confirmed`; user
rejections block future model proposals even across WebDAV merges.

E. **Incremental maintenance**. Invalidated nodes remain in history but are
excluded from retrieval and traversal. Full temporal edge revalidation,
entity canonicalization and automatic graph pruning remain future work.

## Stability and evaluation gates

- Measured extraction precision, wrong fact persistence rate and duplicate
  rates from a *held-out* corpus of user tasks; ordinary chatter should
  produce zero nodes.
- Regression tests for exact evidence anchoring, secrets, time reversal,
  conflicting edits, same-topic false positives, cross-scope isolation,
  graph cycle/path truncation, tombstones and WebDAV schema upgrades.
- Retrieval quality measured at a *fixed context budget* (accuracy of seed
  retrieval versus graph-expanded retrieval, false relation rate, latency).
  Do not turn on automatic graph expansion if its errors outweigh recall gains.
- An inspection/editor exists for review statuses. Auto-consolidated
  observations or mental-model rewriting remain disabled until explicitly
  designed and separately evaluated.

## Current opt-in/privacy

Retain is off by default. When enabled, it makes an extra request to the
same configured model provider using the latest bounded user message; this
may take additional time and token charges. Persisted evidence is a short
literal quote and opaque run hash, not raw chat history. Known secret/PII
patterns are blocked conservatively, not with a guarantee; users can
 inspect/delete retained nodes. Vector embedding calls are a **separate** opt-in:
 query and uncached memory text are sent to the selected compatible Provider,
 and the vector cache remains local. WebDAV uploads are AES-GCM encrypted with
 independent passphrase; schema v3 syncs node and review metadata, not vectors.
