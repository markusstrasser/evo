# Evo Kernel Refactor — Event Log & Incremental Derivation

**Session:** 8799d138
**Date:** 2026-04-20 (revised twice; see §8)
**Scope:** Strictly-better architectural refactors. No backward compatibility.
**Status:** v3 — trimmed to minimum elegant core. Scale-driven machinery
(branch-aware checkpoints, LRU eviction, on-disk persistence, per-plugin
delta logic) is described but deferred until profile shows need.

## Guiding constraint

Every phase must land in a system that is **simpler or no more complex**
than today. Any machinery that solves a scale problem evo doesn't have
(thousands-of-blocks backlinks recompute, long-session op log RAM) is
structure-only: the protocol/shape goes in, the implementation waits.

---

## 1. Motivation

Evo names itself an "event-sourced UI kernel" and commits to the three-op
primitive (`create-node`, `place`, `update-node`). But three pieces of the
current implementation don't live up to that framing:

- **History stores full-db snapshots**, not the op sequence. Undo is
  restore-snapshot, not replay-log. The `:history` map also lives *inside*
  the db it records, which makes any move toward canonical-log-state
  structurally circular.
- **Derived indexes recompute globally on every transaction.**
  `plugins/run-all` (`src/kernel/db.cljc:106`) hands the whole new db to
  every plugin. `plugins.backlinks-index` regexes every block on every
  keystroke.
- **Markdown serialization drops block identity.** Reload mints fresh
  UUIDs. Today this is invisible; the moment cross-page block references
  exist (or multi-device sync via iCloud), it's silent data corruption.

With the auto-save-to-dirty-pages fix already shipped (`0ee71536`), typing
latency is fine at the current 3-file scale. At Logseq-graph scale
(thousands of blocks, years of edits), the backlinks recompute dominates
and the snapshot-history RAM footprint becomes material. Fixing either
piece-meal would duct-tape the structural problem: evo isn't yet the
event-sourced kernel its own docs describe. This plan is that alignment,
done in verifiable steps.

---

## 2. Prior Art in Evo (do not repeat)

| Idea | Status | Evidence |
|---|---|---|
| Incremental derivation | Never tried. Full recompute is baseline. | `derive-indexes` at `src/kernel/db.cljc:89` |
| Op log as history source | Never tried. Snapshots chosen. | `kernel.history` stores `{:db :session}` inside db |
| Stable block IDs in markdown | Never tried; Logseq-parity doc deliberately avoids block refs | `docs/LOGSEQ_PARITY_EVO.md` |
| Post-commit event bus | **Tried, rejected.** | Nexus removed in `adc3a5b9` (2026-03-08) |
| Data-driven plugin manifest | **Tried, rejected.** | `plugins.edn` + runtime created in `555867bb` (2025-11-20) and deleted in `df8175dd` (2025-12-16) |

This plan does not re-propose the bus or the manifest. Neither has gained
a new consumer.

---

## 3. Invariants This Plan Preserves

These are existing evo invariants that the refactor must not violate.
Listed here to keep the critique loop honest:

1. **Three-op invariant.** All state changes reduce to `create-node`,
   `place`, `update-node`. Deletion is move-to-`:trash` via `:place`, not
   a fourth op.
2. **Kernel purity.** Zero imports from `shell/`, `components/`, or
   `keymap/` in `src/kernel/`.
3. **Ops are pure data.** Every UUID and timestamp the op carries is
   minted at the shell/intent layer *before* the op is constructed. The
   kernel's reducer does not call `random-uuid` or read a clock. This is
   already true in current code (grepped `src/kernel/` — zero entropy
   sources); Phase B promotes it from accident to invariant with a CI
   check.
4. **REPL-verifiable in 30 seconds.** Every new primitive has a fixture
   scenario in `dev/repl/` that demonstrates it.

---

## 4. Plan

Four phases, reordered after critique. Each ships independently, each has
a clean revert point, and each leaves the system fully usable.

### Phase A — Externalize `:history` from the DB (prerequisite, small)

**Why first.** Phase B (op log) checkpoints the db. If `:history` is
*inside* the db (as it is today — `src/kernel/history.cljc` stores
`{:past :future :limit}` under `:history`), each checkpoint recursively
embeds log-like history. The whole Phase B design collapses unless
history is outside the db first. Cross-model critique flagged this as the
single highest-structural-risk assumption in the first draft of this plan.

**Changes.**
- Move `:history` out of the db map into a separate atom `!history` (or
  integrate it into Phase B's `!log` directly — see §6).
- Update `kernel.history` to operate on `(history-val, db-val) → new-history`
  pure functions; remove `get-history`, `strip-history`, etc.
- Update the `shell/executor` call site (one place) that currently records
  history by `assoc`ing into the db.

**Scope.** Mechanical. ~2 hours. No semantic change to undo/redo behavior.

**Verification.** Existing undo/redo tests still pass. DB schema is
strictly smaller: no `:history` key.

---

### Phase B — Op Log as Canonical State (lean first cut)

**Goal.** The db becomes a pure function of an append-only op log. Undo
is log rewind. Time-travel debugging is `(head-db-at log op-id)`. Derived
indexes continue to use the current `derive-indexes` full-recompute — this
phase does NOT require Phase D.

**The shape (lean).**

```clojure
;; Kept OUTSIDE the db value (Phase A established this).
!log :: Atom<{:ops         [{:op-id, :prev-op-id, :timestamp,
                             :intent, :ops} ...]   ; append-only vector
              :head        op-id
              :checkpoint  {:op-id ..., :db ...}}>  ; ONE checkpoint

;; DB is a fold from the checkpoint to head.
(defn head-db [log] ...)   ; re-folded on head change; not memoized yet
```

Key properties (lean):
- **Append-only.** Ops are never mutated or removed from `:ops`.
- **Linear undo = head rewind.** `undo` sets `:head` to `prev-op-id`.
- **Prune-on-branch.** If `:head` is rewound and a new op is appended,
  orphaned-branch ops are dropped from `:ops` (matches current snapshot
  semantics — `record` clears `:future` on new action).
- **Single checkpoint.** The most recent one, period. Refreshed every N
  ops (start: N=100). No branch-tree, no LRU, no cache of many.
- **No on-disk checkpoint.** Cold start replays from `:ops` head. At
  evo's current scale this is sub-millisecond. Revisit when it isn't.
- **No memoization of `head-db`.** Recompute on every `:head` change.
  Cheap at present scale. Add memoization if profiling shows need.

**Deferred machinery (describe, do not build).**
- **Branch-aware checkpoint store.** If branch-retention becomes a
  requirement (audit use cases), replace the single checkpoint with a
  map keyed by op-id with descent-pruning.
- **LRU cache of checkpoints.** If re-folding from the single checkpoint
  becomes expensive on long head-jumps, cache multiple checkpoints with
  LRU eviction.
- **On-disk checkpoint persistence.** Persist the checkpoint to
  IndexedDB when cold-start replay exceeds interactivity budget.
- **`head-db` memoization.** Cache by `:head` op-id if render-path
  profile shows redundant folds within one frame.

Each deferred piece is small and mechanical *once* profile says it's
time. Building them now is preemptive.

**Op envelope.**

```clojure
{:op-id       #uuid "..."         ; minted at shell layer, pre-dispatch
 :prev-op-id  #uuid "..."         ; causal chain (= previous :head)
 :timestamp   ms                  ; minted at shell layer
 :intent      {:type :insert-block :page-id "..." ...}   ; audit trail
 :ops         [{:op :create-node ...}
               {:op :place ...}]} ; the three-op primitives
```

`:intent` captures the user-level action that produced `:ops`. Devtools
and replay debugging both need this distinction — "user pressed Enter"
vs. "three low-level ops happened."

**Derived-indexes contract during Phase B.** Unchanged from today.
`head-db` invokes the current `derive-indexes` full-recompute. Cost per
head-change is O(graph × plugins). The memoization on `:head` op-id means
repeated reads within a frame are free. Phase D replaces this with
`apply-tx` for plugins that implement it.

**Why this is strictly better than snapshot history.**
- **Memory.** Snapshot history at 50 × full-db grows linearly with graph
  size, multiplied 50×. Op log + sparse checkpoints grows with edit
  *activity*, bounded by branch-GC + checkpoint eviction.
- **Audit trail.** Every state change has a causal, intent-annotated
  record. Devtools time-travel is `(head-db-at log op-id)`, one function.
- **Property-test harness.** Phase D's delta plugins can be fuzzed
  against the recompute oracle by replaying random op sequences from the
  log. Without Phase B there's no deterministic generator. This is the
  reason Phase B precedes D.

**What this plan does NOT claim.**
- **Not a CRDT.** Tree-edit ops with sibling-positional semantics don't
  naturally form a semilattice; naive op-union does not converge. Move-
  tree CRDTs are a known hard problem, out of scope. If future multi-
  device sync wants CRDT semantics, that's a separate design; the log is
  merely a prerequisite for any future sync work, not a solution to it.
- **Not a replacement for `!db` watchers.** UI still subscribes to a
  `!db-view` derived atom whose value is `(head-db @!log)`; Replicant
  re-renders when `:head` changes. Watcher ergonomics are preserved.

**Risk.**
- Branch-GC policy choice affects undo semantics. Chose "prune on
  divergence" to match current behavior; documented as a tunable if
  audit requirements change.
- Checkpoint eviction tuning is empirical. Start conservative (keep 5),
  measure, adjust.
- Cold-start from disk-checkpoint requires the log tail (ops after the
  checkpoint) to also be persisted. Storage layer already writes
  markdown files; log persistence is additional. Concrete: one IndexedDB
  store for the log, one for the latest checkpoint. Established browser
  primitive.

**Size (lean).** ~1 day. `!log` atom + `head-db` fold + single-checkpoint
maintenance + executor rewire + undo/redo as head-rewind. Kernel three-op
primitives are unchanged.

**Migration order within Phase B.**
1. Introduce `!log` and `head-db` alongside current `!db`. Make `!db` a
   derived view of `!log`. All watchers keep working.
2. Rewire undo/redo to `:head` rewind. Delete `kernel.history`
   snapshot code. Undo semantics identical from the user's POV.
3. Flip executor to append to `!log` instead of `reset! !db`. `!db`
   becomes a pure derived atom.
4. Add checkpoint cadence + eviction + on-disk persistence.
5. Benchmark cold-start, typing latency, undo latency with and without
   checkpoints.

---

### Phase C — Stable Block Identity in Markdown (independent, safety)

**Goal.** Markdown round-trip preserves block IDs. Loading a page twice
yields the same `:nodes` keys (modulo actual content changes).

**This is a capability, not a bug fix.** Evo today has no cross-page
block references (`docs/LOGSEQ_PARITY_EVO.md` deliberately avoids them).
Without block refs, reload-mints-new-UUIDs is silent but harmless.
Phase C adds a capability: *stable identity across reload* — which
unlocks:
- Cross-device consistency via iCloud (same file = same IDs on every
  device).
- Future block-ref or embed support.
- External tool compatibility (Logseq reads/writes `id:: uuid`).

**The cost.** Every block-line now has a UUID property. Git diffs on
prose edits carry a UUID line (unchanged, but present). For pure-
markdown users this is noise. For any future sync or block-ref
consumer, it's essential. Honest framing: trade present diff cleanliness
for future identity safety.

**Scope.**
- Serializer (`src/shell/storage.cljs` `block->markdown`): append
  `id:: <uuid>` as a trailing Logseq-style property line under each
  block's content.
- Page properties (`title::`) use Logseq's page-property syntax (leading
  `title:: ...`), not the block-property syntax — distinct formats, must
  not be conflated.
- Parser (`markdown->ops`): when an `id::` property is present on a
  block, use that UUID; otherwise mint a new one.
- **Total rules for malformed input:**
  - Duplicate `id::` across blocks → keep the first, re-mint the
    second, log a warning.
  - Block with no `id::` → mint on import (same as today's behavior).
  - Block whose `id::` was manually deleted in an editor → mint on
    import (it's a new block from the parser's POV).
  - Malformed `id::` value (not a UUID) → mint + warn.

**Why strictly better at present.** Correctness floor: identity is now a
property of on-disk content, not a coincidence of in-memory construction.
Zero runtime cost; format change only. Independent of A/B/D — can ship
in any order.

**Risk.** Low. Contained to `storage.cljs`. Round-trip property test:
`(= (serialize-then-parse db) db)` on random fixtures.

**Size.** ~2-3 hours including total-rules tests.

---

### Phase D — Delta-aware Plugin Protocol (surface only; implementations deferred)

**Goal (lean).** Put the protocol surface in place. Migrate every existing
`compute-fn` to `initial`. No plugin implements `apply-tx` yet.

**Goal (eventual, when profile demands).** Selected plugins implement
`apply-tx` for per-transaction incremental maintenance, validated against
`initial` via fuzz-replay from the op log (Phase B).

**Prerequisite for the eventual goal.** Phase B. The property-test
harness that validates `apply-tx` correctness needs deterministic replay;
the log provides that.

**The contract.**

```clojure
(defprotocol Derived
  (initial  [this db]
            "Compute index from scratch. This is the oracle spec.
             MANDATORY.")
  (apply-tx [this prev-index db-before tx-ops db-after]
            "Return new index after applying a whole transaction's ops.
             OPTIONAL — return ::recompute to fall back to initial.
             When implemented, must satisfy:
               (apply-tx (initial db-before) db-before ops db-after)
             = (initial db-after)
             on all inputs. CI-fuzzed against initial."))
```

Default behavior: a plugin that doesn't implement `apply-tx` (or returns
`::recompute`) gets full-recompute. Identical to today's behavior. So
Phase D's first cut is a pure rename — no behavior change.

Key properties:
- **Pure.** `apply-tx` and `initial` are pure functions. No atoms, no
  closures, no I/O.
- **Transaction-level, not per-op.** User actions like indent-subtree or
  paste-multiline emit multi-op transactions. Per-op dispatch would
  expose indexes to invalid intermediate states (block created but not
  yet placed). `tx-ops` is the whole vector from one `apply-intent!`
  call.
- **Recompute is the oracle.** `initial` is the normative definition;
  `apply-tx` is a performance optimization. CI property-test fuzzes
  random tx sequences and asserts `apply-tx ≡ initial` on every step.
  Any plugin can elect not to implement `apply-tx` — the kernel falls
  back to `initial(db-after)` silently. Dev-mode flag reports fallback
  frequency; regression = fail loudly.
- **Isolation.** Plugins read only: canonical db (`:nodes`,
  `:children-by-parent`, `:roots`), kernel-maintained core indexes
  (`:parent-of`, `:prev-id-of`, `:next-id-of`, `:index-of`, `:pre`,
  `:post`, `:id-by-pre`), and their own `prev-index`. **Plugins cannot
  read other plugins' indexes.** This eliminates the cross-plugin
  dependency DAG problem by construction — no topological ordering
  needed.
- **No `:delete` op.** There is no fourth op. Where backlinks needs to
  "delete a page," that's a `:place` op moving the page to `:trash`.
  Plugins branch on target-parent, not on op-type.

**Eventual `apply-tx` migration table (do not build until profile shows need).**

| Index | `apply-tx` formulation | Priority |
|---|---|---|
| `:backlinks-by-page` | On `:update-node` where `:text` changed: diff old vs new `[[refs]]` by set difference. On `:place` of a page to `:trash`: remove entries targeting that page. O(\|Δrefs\|). | First candidate — biggest delta/initial ratio |
| `:parent-of` | `:place` ops are the only source of change. Each `:place` updates one entry. O(\|place-ops\|). | Second |
| `:prev-id-of` / `:next-id-of` | `:place` changes at most 4 sibling links per op. O(\|place-ops\|). | Second |
| `:index-of` | Re-derive for the one parent whose children list changed. | Second |
| `:pre` / `:post` / `:id-by-pre` | Tree traversal. Full recompute is the clean form; delta version isn't clearly simpler. | **Never migrate** |

**What this plan does NOT claim.**
- **Not Electric's `incseq`.** Electric's diffs are first-class
  composable values (semigroup `combine`). This plan uses ops-as-deltas:
  plugins receive transaction ops, not pre-computed collection diffs.
  Less machinery, less expressive. If a future plugin needs composable
  `Δ + Δ → Δ`, introduce an explicit diff value type then — not now.
- **Not datascript/datalevin automatic IVM.** Those systems expose
  transaction reports (what changed) but do not auto-maintain arbitrary
  reactive queries via true differential dataflow. This plan is closer
  to their tx-report model than to Differential Dataflow proper.
- **Not demand-driven (Adapton).** This is push-based: every transaction
  updates every eager index. Alternatives considered: Adapton-style
  rebuild-on-read would defer backlinks recompute until the panel is
  visible. Rejected for now — most indexes are consumed on every render
  (all the core traversal indexes), so push-eager is right for them; for
  rarely-consumed indexes, the UI layer can gate visibility before the
  index is even registered. Revisit if profiling shows hidden panels
  dominating cost.

**Why strictly better than current full-recompute.**
- Cost of a transaction: O(\|tx-ops\| × plugin-count × per-plugin-delta)
  instead of O(plugin-count × graph). For typical single-block edits,
  O(1) per plugin.
- Oracle-validated: `initial` stays as the spec; `apply-tx` is a
  checkable optimization, not a hand-maintained second source of truth.
- Composes with Phase B's log: replay-fuzz tests drive `apply-tx`
  correctness at CI time.
- Kernel stays pure. Plugins stay pure. Three-op invariant preserved.

**Risk.**
- Delta logic is more bug-prone than pure recompute. Mitigation: the
  oracle harness catches divergence on any fuzzed sequence; plugin
  authors who don't implement `apply-tx` get the safe default
  automatically.
- Per-plugin speedup is empirical. Some plugins may be net-slower in
  `apply-tx` form for small graphs. Mitigation: measure first, keep
  `initial` as the fallback; don't force every plugin to migrate.

**Size (lean).** ~30 LOC. Define the protocol; migrate existing
compute-fns to `initial`. That's it. Oracle-harness and per-plugin
`apply-tx` implementations wait for a profile signal.

---

## 5. What We're NOT Doing (and Why)

- **Post-commit event bus.** Rejected by Nexus removal (`adc3a5b9`).
  Still no new consumer. Direct dispatch remains simpler.
- **Data-driven plugin manifest.** Rejected in `df8175dd` because nothing
  consumed it. The `Derived` protocol doesn't require one; inline
  `extend-protocol` works. Revisit only if plugin count passes ~10.
- **CRDT / multi-device sync.** Called out as future work. Not a Phase B
  claim. Separate design problem.
- **Malli specs across the kernel.** The three-op primitive is small;
  specs earn less per line than in sprawling codebases. Out of scope.
- **Replicant slice subscriptions / memoization.** Render is already
  RAF-coalesced. Not a bottleneck.
- **Persisted view state.** (Cursor, folds, zoom.) UX nicety, orthogonal.
- **FSA browser fallback.** Evo is Chromium-only by the existing
  `showDirectoryPicker` gate in `src/shell/storage.cljs`. Scope unchanged.
- **Demand-driven (Adapton) lazy derivation.** Rejected for now; see
  Phase D "what this plan does NOT claim."

---

## 6. Execution Order

```
Phase A  (externalize :history)                  — 2h, prerequisite for B
Phase B  (op log, single checkpoint, lean)       — 1d, depends on A
Phase C  (stable IDs in markdown)                — 3h, independent
Phase D  (Derived protocol, no apply-tx impls)   — 2h, depends on B for harness
```

Lean total: ~2 days. Every phase ends in a system that is simpler or no
more complex than today.

**A→B** is hard (B checkpoints db; history must be out first).

**A→C** and **B→C** are independent. C can land first, between A and B,
or after D.

**B→D** is a soft dependency: D *could* land on top of A without B (with
per-tx dispatch and no log), but the oracle-harness story is much
stronger when fuzzed replay comes from the log itself.

Each phase ships to main independently. Each phase has a clean revert
point. Between phases the system is fully usable.

---

## 7. Open Questions

1. **Branch-GC policy default.** The plan chose prune-on-divergence to
   match current behavior. Audit use cases would prefer retain. Ship
   prune; make it a tunable.
2. **Checkpoint cadence (N).** Default 100 ops per checkpoint is a
   guess. Benchmark Phase B on a 10K-op fixture; tune.
3. **Phase D migration scope.** Which indexes migrate first? Proposal:
   backlinks (biggest win, cleanest delta), then `:parent-of`, then
   siblings. Leave traversal indexes (`:pre`/`:post`) on `initial`
   permanently unless profile shows need.
4. **Oracle-harness failure mode.** Fail CI on any divergence or only on
   unexpected fallbacks? Proposal: fail on divergence always; fallback
   rate is a dashboard metric, not a CI gate.
5. **Log persistence granularity.** Per-op flush (safe, chattier) or
   per-transaction flush (fewer writes, small data-loss window on
   crash)? Proposal: per-transaction.

---

## 8. Revision Log

**2026-04-20 (v3)** — Trimmed to minimum elegant core after reviewing v2
against the constraint "every phase must land a system simpler or no
more complex than today." v2 described the end-state at Logseq scale;
v3 is what should actually ship now.

Removed from v2 (kept as described-but-deferred):
- Branch-aware checkpoint store (v2 described it as a tree; v3 has one
  checkpoint and prune-on-branch semantics that match current snapshot
  history).
- LRU checkpoint eviction.
- On-disk checkpoint persistence.
- `head-db` memoization.
- Per-plugin `apply-tx` implementations (v3 migrates existing compute
  fns to `initial`; no plugin implements `apply-tx` yet).
- Oracle-harness fuzz-replay tests (needed only when a plugin actually
  implements `apply-tx`).

What stays from v2:
- Phase order (A → B → C → D) and all the correctness points the
  critique established: transaction-level `apply-tx` signature, plugin
  isolation rule, UUID-at-shell invariant, three-op invariant,
  externalized history.
- Every deferred piece is described as an upgrade path with a small,
  specific trigger condition. Shape is in place; implementation waits
  for evidence.

**2026-04-20 (v2)** — Rewritten after cross-model critique (Gemini Pro +
Gemini Flash + Gemini + GPT-5.4, dispatched via `/critique --deep`).
Material changes from v1:

- **Reordered phases.** v1 ordered Phase 2 (delta plugins) before Phase 3
  (op log) and claimed the former was a hard prerequisite for the
  latter. Reviewers pointed out the dependency is backwards: deterministic
  replay (log) is the prerequisite for *validating* delta plugins, not
  the other way around. v2 ships log first with full-recompute, then
  delta plugins on top, validated by replay-fuzz.
- **Added Phase A.** v1 skipped externalizing `:history` from the db.
  Checkpointing a db that contains its own history is recursive. v2
  makes this an explicit phase.
- **Protocol is now per-transaction, not per-op.** Multi-op user actions
  (indent-subtree, paste-multiline) would expose plugins to invalid
  intermediate states under per-op dispatch.
- **Recompute promoted from fallback to oracle.** v1 had `::recompute`
  as an escape hatch. v2 makes `initial` the normative spec and
  `apply-tx` a CI-validated optimization.
- **Plugin isolation rule added.** Plugins read only canonical db +
  kernel-maintained core indexes + own prior index. No cross-plugin
  index reads → no topological DAG problem.
- **Removed spurious `:delete` op.** v1 backlinks migration table
  referenced a `:delete` op that doesn't exist; deletion is `:place` to
  `:trash`. Violated the three-op invariant. Fixed.
- **Removed CRDT claim from Phase B.** Tree-edit ops don't form a
  semilattice. Log is a prerequisite for any future sync work, not a
  solution to sync.
- **Reframed Phase C (stable IDs).** From "bug fix" to "capability
  expansion." Block refs don't exist today; identity stability is
  forward-looking, with a real cost (UUID noise in git diffs).
- **Added UUID/timestamp discipline as explicit invariant** (§3.3).
  Already safe in current code; elevated to documented kernel
  guarantee.
- **Added total rules for malformed `id::` input** (Phase C).
- **Corrected prior-art characterization.** datascript/datalevin have
  transaction reports, not automatic differential dataflow. Electric's
  `incseq` uses first-class composable diffs; this plan uses ops-as-
  deltas, which is less expressive — honest framing now.
- **Added branch-aware checkpoint store + on-disk anchor + LRU
  eviction.** v1 hand-waved checkpoint storage; reviewers pointed out
  flat map + in-memory-only + unbounded growth are all real failure
  modes.

v1 findings not adopted: Adapton-style demand-driven derivation
(deferred; most indexes are render-hot so push is right); FSA browser
fallback (out of scope, Chromium-only by existing gate); one hallucinated
Roam 500K-users claim (plan never mentioned Roam).

Full disposition: `.model-review/2026-04-20-evo-kernel-refactor-19b635/`.
