# Kernel Simplification Proposals (2025-09-28)

This document collects design proposals aimed at shrinking complexity in the Evolver kernel while keeping behaviour intact. Each section lists the current touchpoints in this repo, the idea we would port from `/Users/alien/Projects/inspo-clones/`, before/after sketches, and concrete change bullets. No production code has been modified—this is a planning document only.

---

## Integrated Baseline (2025-09-28)

- **Declarative Transaction Pipeline** (formerly Proposal 7) now ships in `src/kernel/core.cljc:550-620` via `default-pipeline`/`run-pipeline`, so stage composition is data-driven out of the box.
- **Data-First Op Registry** (formerly Proposal 8) lives in `kernel.schemas/register-op!` with core registrations at `src/kernel/core.cljc:520-544`, giving one source of truth for schemas, handlers, and metadata.
- **Structured Responses** (formerly Proposal 16) are provided by `kernel.responses` (`src/kernel/responses.cljc:1-43`) and consumed from `kernel.core/evaluate`.
- **Kernel Introspection Toolkit** (covered by the old introspection proposal) now lives in `src/kernel/introspect.cljc:1-68`, so diff/path/trace helpers are part of the baseline API.

---

## Proposal 2 · Incremental Derived Updates Per Operation

**Current touchpoints**
- `src/kernel/core.cljc:609-633` – recomputes the entire derivation after every op.
- `src/kernel/invariants.cljc:20-116` – assumes derived maps are up to date.

**Pain**
Re-deriving the full tree on every op inflates hot-path latency and allocates unnecessary transient maps.

**Inspiration**
- Datascript incremental indexes (`/Users/alien/Projects/inspo-clones/datascript/src/datascript/db.cljc:1400-1475`).

**Proposed change**
Return `{::db db' ::derived-delta {...}}` from primitive ops. Add `kernel.derive/apply-delta` that updates only touched parent/child structures (e.g., update sibling index for `:place`, mark orphan status for `:prune`). `apply-tx+effects*` becomes:

```clojure
(let [{:keys [db' delta trace]} (apply-op-with-delta d op)
      d1 (derive/apply-delta db' delta)]
  ...)
```

**Expected benefits**
- Complexity drops from O(|tree|) to O(|touched-subtree|) per op.
- Enables streaming contexts (LLM planners can preview partial results quickly).

**Implementation notes**
1. Wrap existing primitives (e.g., `place*`) to emit touched parent IDs.
2. Provide a debugging flag that falls back to full derivation for validation.
3. Update `kernel.invariants/check-invariants` to tolerate partial caches when debugging is off.

**Trade-offs**
- More moving parts per primitive: developers must keep both structural logic and delta emissions in sync.
- Requires robust instrumentation to fall back to full derivation when state corruption is suspected (extra tooling effort).

---

## Proposal 3 · `defop` Macro for Composite Operations

**Current touchpoints**
- `src/kernel/sugar_ops.cljc:11-52` – manual destructuring/asserts for each sugar op.
- `src/kernel/schemas.cljc:45-112` – duplicated Malli shapes for each op.

**Pain**
Every new intent requires changes in two files and hand-written asserts.

**Inspiration**
- Pathom `defresolver` (`/Users/alien/Projects/inspo-clones/pathom3/src/main/com/wsscode/pathom3/connect/operation.cljc:425-515`).

**Proposed change**
Add `kernel.intents/defop` macro:

```clojure
(kernel.intents/defop insert
  {:requires [id parent-id]
   :defaults {:type :div :props {}}
   :doc "Create + attach in one op"}
  (-> db
      (K/create-node* {:id id :type type :props props})
      (K/place* {:id id :parent-id parent-id :pos (or pos :last)})))
```
Macro tasks:
- Generate Malli schema and register it with `kernel.schemas`.
- Define the `K/apply-op` method.
- Attach documentation/metadata for tooling.

**Expected benefits**
- One change-site per new intent.
- Automatic docs (macro can register examples).
- Consistent error messages (macro can emit standard `missing-required` exceptions).

**Implementation notes**
1. Define DSL for binding (see Proposal 11 for extensions like coercions).
2. Auto-generate schema union in `kernel.schemas` via a registry atom.
3. Update tests to require `kernel.intents` instead of `kernel.sugar-ops`.

**Trade-offs**
- Macro indirection can obscure runtime behavior for new contributors; detailed docs and REPL helpers mitigate this.
- Debugging macro expansions is slightly heavier than plain functions, but the win in consistency often outweighs the cost.

---

## Proposal 4 · Structured Diff Effects via IncSeq

**Current touchpoints**
- `src/kernel/effects.cljc:3-11` – always emits `:view/scroll-into-view`.
- `src/kernel/core.cljc:632-633` – collects raw effect maps.

**Pain**
Adapters need to recompute DOM diffs even though the kernel has complete structural knowledge.

**Inspiration**
- Electric sequence diff (`/Users/alien/Projects/inspo-clones/electric/src/hyperfiddle/incseq/diff_impl.cljc`).

**Proposed change**
Introduce `kernel.diff/seq-diff` using incseq’s primitives to compare `[:derived :preorder]` before and after each op. `effects/detect` emits normalized diff objects:

```clojure
{:effect :view/diff
 :delta  {:grow 1 :shrink 0 :permutation {...} :change {...}}
 :path   [:derived :preorder]
 :cause  {:op-index i :op (:op op)}}
```

Adapters can dispatch by effect type and apply diffs without re-running tree walks.

**Expected benefits**
- Deterministic, minimal view patches shared across adapters (DOM, TUI, etc.).
- Easier effect testing (diff objects are pure data, see Proposal 10).

**Implementation notes**
1. Port incseq helpers (`empty-diff`, `combine`) into a small namespace.
2. Extend detect to cover node deletions/reorders.
3. Update sanity checks to assert diff shape for inserts/moves.

**Trade-offs**
- Diff computations add CPU work; must ensure the cost scales with touched subtrees (profiling required for large docs like Figma canvases).
- Adapters must understand the diff schema; legacy clients may need shims translating `:view/diff` to imperative updates.

---

## Proposal 5 · Fiber-Orchestrated Effect Runner (Missionary)

**Current touchpoints**
- `src/kernel/core.cljc:602-633` – effect vectors returned but never executed centrally.
- `src/kernel/effects.cljc` – effect definitions are synchronous maps.

**Pain**
Adapters reimplement sequencing, cancellation, and batching logic around effect maps.

**Inspiration**
- Missionary task/fiber primitives (`/Users/alien/Projects/inspo-clones/missionary/src/missionary/core.cljc:260-340`).

**Proposed change**
Create `kernel.runtime` with `effect->task` and `run-effects` helpers using Missionary’s `sp`, `join`, and `compel` executors. Kernel callers call `(runtime/run-effects adapter effects)`; adapters implement effect handlers returning tasks.

```clojure
(defmulti effect->task (fn [_adapter {:keys [effect]}] effect))

(defmethod effect->task :view/diff [adapter e]
  (m/sp (adapter/apply-diff! adapter e)))

(defn run-effects [adapter effects]
  (m/? (apply m/join vector (map #(effect->task adapter %) effects))))
```

**Expected benefits**
- Centralised cancellation semantics (Missionary raises if cancelled).
- Async-friendly: adapters can return non-blocking tasks (fetch data, wait for animation frame).

**Implementation notes**
1. Package Missionary as a dependency (already in inspiration repo).
2. Provide default effect interpreter (noop runner) for tests.
3. Add instrumentation hooks (log each effect start/finish).

**Trade-offs**
- Missionary introduces an additional runtime dependency; teams must be comfortable reasoning about fibers/tasks.
- Async execution complicates deterministic testing—simulator harness (Proposal 10) must emulate scheduling to keep tests reproducible.

---

## Proposal 6 · Transaction Pattern Watchers (Metamorphic)

**Current touchpoints**
- `src/kernel/sanity_checks.cljc:300-471` – test helpers only return booleans.
- Transaction traces produced when `:trace? true` but never interpreted.

**Pain**
Diagnosing regressions requires eyeballing traces; patterns like “insert then orphan” are hard to spot.

**Inspiration**
- Re-frame-10x metamorphic runtime (`/Users/alien/Projects/inspo-clones/re-frame-10x/tools/metamorphic.cljc:1-200`).

**Proposed change**
Add `kernel.audit` with a tiny pattern DSL:

```clojure
(defpattern orphaned-reorder
  (begin :insert)
  (followed-by :reorder #(= (:parent-id %) (:dst %)))
  (end-on :delete))

(defn audit-trace [trace]
  (->> trace (audit/run [orphaned-reorder move-bounce]) audit/summarise))
```

Integrate into `sanity_checks` to surface structured narratives in failure reports.

**Expected benefits**
- Faster debugging (patterns summarise multi-step issues).
- Hooks for LLM agents to reason about kernel history.

**Implementation notes**
1. Port `id-between-xf`/pattern machinery (rename to `kernel.audit.runtime`).
2. Provide a small pattern library (orphans, double deletes, undo-bounce).
3. Update CLI sanity check output to print pattern matches.

---

## Proposal 9 · Schema-Driven State Normalisation (Malli)

**Current touchpoints**
- `src/kernel/core.cljc:210-257` – manual coercions when building derived maps.
- `src/kernel/core.cljc:323-356` – `prune*` walks sets/vectors defensively.

**Pain**
Manual coercion logic is duplicated and brittle when ingesting external data.

**Inspiration**
- Malli transformer stack (`/Users/alien/Projects/inspo-clones/malli/src/malli/transform.cljc:1-160`).

**Proposed change**
Define `::db/normalizer` transformer that canonicalises IDs (UUIDs), vectorises child lists, and keywordises map keys. Run `normalize-db` at transaction boundaries and before invariant checks.

```clojure
(def db-transformer
  (mt/transformer mt/json-transformer
                  (mt/-string->keyword) (mt/-string->uuid)))

(defn normalize-db [db]
  (m/transform db-transformer schemas/db-schema db))
```

**Expected benefits**
- Safer import/export pipeline.
- Simplifies derived code (can assume canonical forms).

**Implementation notes**
1. Extend `schemas/db-schema` with stricter shape expectations.
2. Run `normalize-db` in `apply-tx+effects*` before entering the pipeline.
3. Add tests feeding stringly IDs/props to ensure they normalise.

---

## Proposal 10 · Replicant-Style Mutation Harness for Effects

**Current touchpoints**
- `src/kernel/sanity_checks.cljc:320-363` – verifies effects by checking keywords only.
- `src/kernel/effects.cljc` – effect payloads lack structural detail.

**Pain**
Tests cannot assert how adapters mutate the view; all they see are effect keywords.

**Inspiration**
- Replicant mutation log (`/Users/alien/Projects/inspo-clones/replicant/src/replicant/mutation_log.cljc:1-180`).

**Proposed change**
Introduce `kernel.simulator/mutation-log` that implements the adapter protocol but records pure EDN mutations (children added, attributes set). Example test helper:

```clojure
(defn run-with-simulator [db tx]
  (let [sim (simulator/mutation-log)]
    (-> (core/apply-tx+effects* db tx {:effect-runner #(runtime/run-effects sim %)
                                       :derive derive/*derive-pass*})
        (assoc :mutations @(:log sim)))))
```

**Before**
```clojure
(let [{:keys [effects]} (core/apply-tx+effects* db {:op :insert ...})]
  (is (= [:view/scroll-into-view] (map :effect effects))))
```

**After**
```clojure
(let [{:keys [mutations]} (run-with-simulator db tx)]
  (is (= [[:diff {:grow 1 :change {"x" ...}}]] mutations)))
```

**Implementation notes**
1. Port Replicant’s `log`, `set-parent`, and snapshot helpers (rename to avoid DOM terms).
2. Provide translation functions from diff effect -> mutation log entries.
3. Update sanity checks to use the simulator for richer assertions.

**Trade-offs**
- Simulator must stay aligned with effect schema; add tests to fail if a new effect lacks a simulator handler.
- Recording full snapshots can grow logs; consider configurable depth for large scenes (e.g., game editors).

---

## Proposal 11 · Intent Binding DSL (Compojure)

**Current touchpoints**
- `src/kernel/sugar_ops.cljc:11-52` – manual destructuring and asserts.

**Pain**
We repeat the same `:keys`, `assert`, and conversion boilerplate across ops.

**Inspiration**
- Compojure `let-request`/binding helpers (`/Users/alien/Projects/inspo-clones/compojure/src/compojure/core.clj:84-150`).

**Proposed change**
Build `kernel.intents/let-op` macro mirroring Compojure’s binding forms (`:as`, `:<<` for coercions, `&` rest). `defop` (Proposal 3) expands to `let-op` around handler code, auto-injecting conversions.

```clojure
(defmacro let-op [[bindings op-expr] & body]
  ... ;; auto-bind id, to-parent-id, etc.
)

(kernel.intents/defop move
  [id :<< ensure-node
   from-parent-id
   to-parent-id :<< ensure-node
   pos]
  (K/place* db {:id id :parent-id to-parent-id :pos pos}))
```

**Implementation notes**
1. Copy Compojure’s vector binding walker, adapt for op maps instead of requests.
2. Provide standard coercion fns (`ensure-node`, `parse-index`).
3. Generate helpful error messages (`missing-binding`, `coercion-failed`).

---

## Proposal 12 · Axis Metadata with Integrant-Style Composite Keys

**Current touchpoints**
- `src/kernel/core.cljc:567-600` – ops know their behaviour but no metadata exists.
- `src/kernel/invariants.cljc:20-116` – invariants can’t assert coverage by op category.

**Pain**
We cannot answer “which ops mutate topology?” programmatically.

**Inspiration**
- Integrant annotations/composite keywords (`/Users/alien/Projects/inspo-clones/integrant/src/integrant/core.cljc:1-120`).

**Proposed change**
Add `kernel.ops.meta` with `annotate` and `composite` helpers. Each op registers axes like `:axis/existence`, `:axis/order`.

```clojure
(ops.meta/annotate :kernel/insert {:axes #{:axis/existence :axis/topology}})
(ops.meta/annotate (ops.meta/composite [:axis/topology :axis/order]) {:doc "Reorder within parent"})
```

`apply-op` and the op registry (`kernel.schemas/register-op!`) check `:requires` axes before execution. Invariants can ensure that after a tx touching `:axis/topology`, derived order maps are recomputed.

**Implementation notes**
1. Provide `ops.meta/find-derived` similar to Integrant’s `find-derived` for axis queries.
2. Expose `ops.meta/describe` for tooling and docs.
3. Extend sanity checks to assert that each op has at least one axis annotation.

---

## Proposal 13 · Map Transform Helpers (Medley)

- **Source inspiration**: medley/core (`map-vals`, `map-kv-vals`, `assoc-some`, `update-existing`).
- **Targets**: `src/kernel/core.cljc` sibling/parent derivations, `src/kernel/tree_index.cljc`, response builders.
- **Thesis**: depend on Medley (tiny CLJC lib) or vendor reexport so derived calculators express intent with declarative helpers, eliminating bespoke `reduce-kv` plumbing.

## Proposal 14## Proposal 14 · Rewrite-CLJ Planner Rewriters

**Current touchpoints**
- `src/kernel/sugar_ops.cljc` – complex behaviours encoded manually.
- Planned planner (not yet implemented) will need algebraic simplifications.

**Pain**
Normalising or simplifying transaction plans requires ad-hoc recursion.

**Inspiration**
- cljfmt zipper transforms (`/Users/alien/Projects/inspo-clones/cljfmt/src/cljfmt/core.cljc:20-200`).

**Proposed change**
Add `kernel.planner.rewrite` with helpers:

```clojure
(defn transform [plan pred f]
  (rewrite/apply-zippers plan pred f))

(def remove-noops
  (rewrite/edit-all #(and (= :move (:op %)) (= (:parent-id %) (:from-parent-id %)))
                    z/remove*))
```

Planner pipeline can compose rewrites: `(-> plan remove-noops merge-adjacent-moves ensure-topology)`.

**Implementation notes**
1. Depend on `rewrite-clj` (already via cljfmt) or vendor minimal zipper helpers.
2. Provide EDN-friendly wrappers (no actual code AST, only Clojure data).
3. Add property tests to ensure rewrites preserve semantics (state transitions).

---

## Proposal 15 · Transaction Middleware Stack (Ring)

**Current touchpoints**
- `src/kernel/core.cljc:550-620` – `default-pipeline`/`run-pipeline` handle core stages but there is no composable outer middleware layer yet.

**Pain**
Metrics, audit, undo stack, and dry-run logic must all live inside the loop.

**Inspiration**
- Ring middleware chain (`/Users/alien/Projects/inspo-clones/ring/ring-core/src/ring/middleware/*.clj`).

**Proposed change**
Treat tx evaluation like a Ring handler: define a base evaluator that reads `::db`, `::ops`, `::cursor` from context, then wrap it in middleware functions.

```clojure
(defn wrap-metrics [handler]
  (fn [ctx]
    (metrics/start! (:ops ctx))
    (let [result (handler ctx)]
      (metrics/finish! result)
      result)))

(def tx-handler
  (-> core-evaluator
      wrap-metrics
      wrap-audit
      wrap-dry-run))

(tx-handler {::db db ::ops ops ::opts opts})
```

Middlewares can short-circuit by returning `assoc ctx ::result {:ok? false ...}` similar to Ring’s early responses.

**Implementation notes**
1. Define context schema (keys & expectations) in `kernel.tx.context`.
2. Migrate invariants/effects into middleware wrappers to shrink the base evaluator.
3. Offer extension point for adapters to inject their own middleware (e.g., per-intent authorization).

**Trade-offs**
- More indirection: debugging becomes tracing through middleware layers, so we need good logging.
- Middleware ordering matters; must document canonical stacking (e.g., metrics before audit before effects) to avoid subtle bugs for clients like Figma or VR runtimes.

---

## Proposal 23 · Alias Expansion Layer (Replicant)

**Current touchpoints**
- Planned high-level intents (`src/kernel/intents`) and `src/kernel/environment` lack a formal macro/alias mechanism.
- Domain clients (Figma, Roam Research, Obsidian, game engines) need reusable macros (e.g., “insert heading block”, “wrap selection in autolayout”).

**Finding**
Replicant resolves aliases during rendering (`replicant/mutation_log.cljc`, README alias section) to translate placeholders into concrete DOM instructions.

**Proposal**
Introduce an alias registry that maps symbolic commands to canonical intent sequences.

```clojure
(defn register-alias! [k f]
  (swap! aliases assoc k f))

(defn expand-alias [state {:keys [alias args]}]
  (if-let [f (@aliases alias)]
    (f state args)
    (throw (ex-info "Unknown alias" {:alias alias}))))
```

Aliases run before intent grammar compilation (Proposal 20), producing standard words that planners and LLM agents can inspect.

**Expected benefits**
- Bridges high-level product commands to kernel primitives without bloating the core op registry.
- Encourages pure, testable alias expansions that can be documented per client domain.

**Implementation notes**
1. Mirror Replicant’s alias approach: store expansion functions in pure data, allow per-client namespaces (`:figma/stack`, `:roam/query`).
2. Integrate alias expansion into the planner pipeline prior to trie compilation.
3. Provide tooling to list aliases, sample expansions, and required capabilities.

**Trade-offs**
- Alias explosion risk; needs governance (linting, documentation) to avoid overlapping semantics.
- Extra lookup adds latency; employ caching for hot aliases in high-frequency clients (e.g., design tools).

---

## Proposal 24 · Domain Schema Plugins (Slate)

**Current touchpoints**
- `src/kernel/schemas.cljc` and planned planners lack domain-specific normalization hooks.
- Domain products (Slate-like editors, Figma canvases, VR scene graphs) need custom invariants—tables with equal row lengths, autolayout constraints, prefab hierarchies.

**Finding**
Slate exposes a plugin-based schema/normalizer system (see `/Users/alien/Projects/inspo-clones/slate/packages/slate`) where plugins enforce constraints and transform documents without modifying the core.

**Proposal**
Expose a plugin pipeline in the kernel to mutate transactions before/after evaluation.

```clojure
(defrecord Plugin [validate normalize transform])

(defn register-plugin! [plugin]
  (swap! plugins conj plugin))

(defn apply-plugins [db ops]
  (reduce (fn [ctx {:keys [validate normalize transform]}]
            (-> ctx
                (update :ops #(map (partial transform (:db ctx)) %))
                (update :ops #(do (doseq [op %] (validate (:db ctx) op)) %))
                (update :db normalize)))
          {:db db :ops ops}
          @plugins))
```

**Expected benefits**
- Domains can register validators/normalizers so the kernel becomes a trivial substrate for Slate-style editors, design tools, or game engines.
- Keeps core primitives lean while letting product teams layer invariants (e.g., Figma autolayout) through plugins.

**Implementation notes**
1. Define plugin protocol with Malli schemas for predictable composition order.
2. Run plugins before incremental derive (Proposal 2) to keep deltas consistent.
3. Ship reference plugins (rich text, outliner, scene graph) to demonstrate extensibility.

**Trade-offs**
- Plugin ordering needs governance to avoid conflicting transforms; add introspection tooling.
- Extra validation incurs cost; allow opt-in "fast mode" once plugins are battle-tested.

---

## Proposal 25 · Invertible Operations & History Bridge (Slate)

**Current touchpoints**
- `src/kernel/core.cljc:632-633` accumulates effects but doesn’t expose inverse ops.
- No canonical mapping between external rich-text operations (Slate) and kernel primitives for undo/collab.

**Finding**
Slate models every change as an `Operation` with explicit inverses (`packages/slate/src/interfaces/operation.ts`). This powers history and collaborative syncing because ops form a groupoid with inverses.

**Proposal**
Define invertible kernel ops and adapters to translate Slate operations into kernel transactions (and vice versa). Provide `inverse-op` functions so undo/redo/collab becomes first-class.

```clojure
(defprotocol InvertibleOp
  (apply-op [op db])
  (inverse [op db]))

(defn slate->tx [slate-op]
  (case (:type slate-op)
    "insert_text" [{:op :update-node ...}]
    "move_node"   [{:op :move ...}] ...))
```

**Expected benefits**
- Makes Slate-style editors a trivial special case: their operation streams map directly to kernel transactions.
- Enables undo/redo and CRDT reconciliation by storing inverses alongside forward ops.

**Implementation notes**
1. Wrap primitive ops in `InvertibleOp` records implementing `inverse` (ties into Proposal 21’s `TxOp`).
2. Build translation layer `slate->tx` / `tx->slate` covering core op types (insert/move/set/remove).
3. Extend history tooling to use inverses to compute undo stacks, simplifying integrations with Slate/Figma/etc.

**Trade-offs**
- Maintaining bidirectional mappings adds complexity; requires round-trip tests to ensure fidelity.
- Inverse computation may require extra metadata (previous parent, offsets), increasing transaction payload size.

---

## Proposal 26 · Graph Reference Semantics (Logseq-style Outliner)

**Current touchpoints**
- `src/kernel/core.cljc` supports refs but lacks higher-level semantics (backlinks, block embeds).
- Domain clients like Logseq/Roam need graph queries, backlinks, transclusions as first-class features.

**Finding**
Outliner tools maintain bidirectional graphs of blocks/pages. Evolver’s `:refs` map can encode this, but structured indices and queries make those apps a trivial specialization.

**Proposal**
Build a graph layer that indexes refs, exposes backlink queries, and enforces referential integrity.

```clojure
(defn index-graph [db]
  {:backlinks (build-backlinks (:refs db))
   :forward   (:refs db)
   :orphans   (find-orphan-refs db)})

(defn backlinks [graph id]
  (get-in graph [:backlinks id] #{}))
```

**Expected benefits**
- Makes Logseq/Roam-style apps trivial: register a graph indexer, reuse kernel ops for block moves/refs.
- Enables graph queries/transclusions for other domains (notes, design systems, game narrative graphs).

**Implementation notes**
1. Maintain incremental graph index alongside derived data (ties into Proposal 2).
2. Provide query helpers (`backlinks`, `forward-refs`, path finders) with pure semantics for REPL/LLM clients.
3. Enforce referential integrity (auto-flag dangling refs, suggest repairs).

**Trade-offs**
- Additional indexing cost; require incremental updates to stay fast on large graphs.
- Domain-specific queries may demand richer schemas (properties, tags); keep APIs extensible.

---

## Summary Table

| # | Theme | Current hotspots | Inspiration |
|---|-------|------------------|-------------|
| 1 | Derivation registry | `core.cljc:178-257` | Pathom plugins |
| 2 | Incremental derive | `core.cljc:609-633` | Datascript `with-datom` |
| 3 | `defop` macro | `sugar_ops.cljc`, `schemas.cljc` | Pathom `defresolver` |
| 4 | Diff effects | `effects.cljc` | Electric incseq |
| 5 | Effect runner | `core.cljc:602-633` | Missionary fibers |
| 6 | Pattern audit | `sanity_checks.cljc` | Re-frame-10x metamorphic |
| 7 | Pipeline | `core.cljc` loop | MCP edit pipeline |
| 8 | Op registry | `core.cljc:567-600` | Reitit router |
| 9 | Normaliser | `core.cljc` derivation | Malli transformers |
| 10 | Mutation log | `sanity_checks.cljc` | Replicant mutation log |
| 11 | Binding DSL | `sugar_ops.cljc` | Compojure bindings |
| 12 | Axis metadata | `core.cljc`, `invariants.cljc` | Integrant annotations |
| 13 | Map helpers | `core.cljc` loops | Medley core |
| 14 | Planner rewrites | Planned planner | cljfmt/rewrite-clj |
| 15 | Middleware | `core.cljc` loop | Ring middleware |
| 16 | Intent responses | `core.cljc`, future intents | Ring response helpers |
| 17 | Tx middleware | `core.cljc:602-633` | Ring cookies/session |
| 18 | Domain scenarios | `docs/mlir_ui_domains.md` | Logseq workflows |
| 19 | Async hooks | `core.cljc`, adapters | Ring async |
| 20 | Intent grammar | `intents` planner | Reitit trie |
| 21 | Tx monoid | `core.cljc` loop | Datascript transact |
| 22 | Effect functors | `effects.cljc` | Missionary + Ring async |
| 23 | Alias layer | `intents` pipeline | Replicant aliases |
| 24 | Schema plugins | `schemas.cljc`, planners | Slate plugins |
| 25 | Invertible ops | `core.cljc`, adapters | Slate operations |
| 26 | Graph semantics | `refs`, derived index | Logseq/Roam graph |

Each proposal builds on proven patterns from the inspiration repos and references the exact Evolver locations they would reshape.

## Proposal 17 · Transaction Middleware Inspired by Ring’s Cookie/Session Layers

**Current touchpoints**
- `src/kernel/core.cljc:602-633` – no separation between request mutation (reading derived data) and response mutation (writing effects).
- `src/kernel/sanity_checks.cljc` – manual effect assertions.

**Finding**
Ring middleware (e.g., `ring.middleware.params`, `cookies`, `session`) cleanly separates request enrichment and response finalization. Each middleware has `*-request` and `*-response` helpers, and `wrap-*` composes them. `wrap-session` (`ring/middleware/session.clj:78-150`) is the template: normalise the request with `session-request`, run the handler, then normalise the response with `session-response`, reusing cookie helpers for persistence. That pre/post symmetry is exactly what the kernel is missing today.

**Proposal**
Introduce paired helpers in `kernel.tx.middleware`:
- `derive-request` – ensures context contains latest derived maps.
- `effects-response` – runs detected effects via the missionary runtime before returning.

`wrap-tx` would mirror Ring’s structure:

```clojure
(defn wrap-derive [handler]
  (fn [ctx]
    (-> ctx derive/request handler effects/response)))
```

This complements the existing transaction pipeline (`kernel.core/default-pipeline`) by ensuring each stage has request/response symmetry.

**Implementation notes**
1. Port the pattern from `ring.middleware.session`: split `derive-request`/`derive-response` to manage caches and effect dispatch separately.
2. Provide toggles (similar to `:set-cookies?`) to skip effect execution for dry runs.
3. Document stacking order guidelines (e.g., derive before audit).

---

## Proposal 18 · Intent Parameter Stack Models (Logseq Domain Study)

**Context**
To build MLIR-aware clients, we need concrete UX domain cases. Logseq’s README offers workflows (outliner editing, graph relationships) that match Evolver’s goals.

**Finding**
Logseq’s workflow (block editing, references, property pages) mirrors our envisioned MLIR UI, but the README isn’t available locally yet. We need to ingest that product doc (or equivalent stakeholder notes) to capture concrete scenarios.

**Proposal**
Once the Logseq docs are imported, document domain-specific intent bundles:
- **Block Editing**: insert, move, indent/outdent, merge – maps to `:insert`, `:move`, `:reorder` sequences.
- **References**: link/unlink pages, aliases – maps to `:add-ref`, `:rm-ref` with relation metadata.
- **Properties & Queries**: update node props, run filters – maps to `:update-node` enhancements.

Add an appendix `docs/mlir_ui_domains.md` summarizing these workflows and how the kernel’s axes support them. Use the scenarios to drive planner acceptance tests.

**Implementation notes**
1. Import Logseq README (or equivalent domain notes) and extract daily journal, property edit, and query workflows.
2. For each workflow, list the sequence of kernel ops required today and flag missing sugar ops.
3. Feed these sequences into the metamorphic audit system (Proposal 6) as expected patterns.

---

## Proposal 19 · Async Effect Hooks (Ring Async Util)

**Current touchpoints**
- `src/kernel/core.cljc` – currently synchronous effect detection.

**Finding**
`ring.util.async` and websocket helpers show concise wrapper functions for providing async callbacks (`respond`, `raise`). These can inform the missionary runtime (Proposal 5) by aligning effect execution with callback-style adapters.

**Proposal**
Define effect adapters that accept both sync and async arities (similar to Ring handlers). Example skeleton:

```clojure
(defprotocol EffectHandler
  (handle [adapter effect]
    [adapter effect respond raise]))
```

Missionary tasks can wrap callback-based handlers, giving adapters flexibility without rewriting their interface.

**Implementation notes**
1. Review `ring.util.async` to design the dual-arity call signature.
2. Provide default implementations that bridge to missionary tasks.
3. Update docs to explain synchronous vs asynchronous effect execution.

---

## Proposal 27 · Declarative Invariant Deck

**Current touchpoints**
- `src/kernel/invariants.cljc:1-109` encodes every rule inline with `doseq` + `assert` loops.
- `src/kernel/sanity_checks.cljc:140-210` depends on exception message strings emitted by those asserts.

**Pain**
Invariant logic is monolithic and stringly typed; extending or disabling rules requires editing the big function and chasing message text throughout checks.

**Inspiration**
- Malli instrumentation registry (`malli/src/malli/instrument.clj:12-120`) stores instrumented entries as data and lets callers filter/enable them at runtime.

**Proposed change**
Define a registry of invariant maps (`{:id :adjacency-symmetry :requires #{:derived/parent-id-of} :check fn}`), compile it once, and have `check-invariants` run each `:check`, returning structured error maps. Provide helpers to enable/disable invariants or throw on the first failure.

**Expected benefits**
- Registry is introspectable (list active rules + docs).
- Tests and adapters receive rich error maps instead of strings.
- Rules can be toggled per environment (e.g., skip expensive ones in perf runs).

**Implementation notes**
1. Add `kernel.invariant_registry` with a `definvariant` macro mirroring Malli’s `collect!`.
2. Port existing rules into the registry and update `check-invariants` / sanity checks to consume structured failures.
3. Provide a compatibility wrapper that throws on the first failure so current callers continue to work during migration.

---

## Proposal 28 · Node Entity Lenses

**Current touchpoints**
- `src/kernel/introspect.cljc:19-64` and `src/kernel/sanity_checks.cljc:52-110` manually reach into `:nodes`/`:derived` maps.

**Pain**
Consumers memorise internal layout and repeat boilerplate to fetch parents, children, or props, making downstream tooling verbose and fragile.

**Inspiration**
- Datascript entity wrappers (`datascript/src/datascript/impl/entity.cljc:1-200`) expose records that behave like CLJ maps with lazily cached attributes.

**Proposed change**
Introduce `kernel.entity/entity` returning a `NodeEntity` record implementing a small protocol (`ent/id`, `ent/parent`, `ent/children`, `ent/props`). Internals handle lookups so callers treat nodes as first-class objects.

**Expected benefits**
- Ergonomic API for REPLs, tests, and adapters.
- Centralises derived lookups, easing future schema changes.
- Enables richer metadata (e.g., `ent/path`, `ent/subtree-size`) without touching every caller.

**Implementation notes**
1. Create `kernel/entity.cljc` (shared CLJ/CLJS) mirroring Datascript’s cross-platform tricks.
2. Refactor tooling namespaces to consume the new API, ensuring no behaviour change.
3. Add property tests comparing entity reads vs. raw map lookups to guard against regressions.

---

## Proposal 29 · Scenario Matrix for Sanity Checks

**Current touchpoints**
- `src/kernel/sanity_checks.cljc:19-225` reimplements custom harness helpers (`test-safely`, `test-throws`).

**Pain**
Adding a new scenario means duplicating try/catch boilerplate and string expectations; reporting is inconsistent.

**Inspiration**
- Malli’s table-driven tests (`malli/test/malli/transform_test.cljc:1-60`) declare input/output pairs while a generic harness handles assertions.

**Proposed change**
Represent each sanity check as a data map (`{:id :full-derivation :run fn :expect predicate :doc string}`) and run them through a generic `run-scenarios` executor that yields uniform result maps.

**Expected benefits**
- Dramatically less ceremony for new checks.
- Structured outputs ready for dashboards/CLI.
- Easy filtering (`run-scenarios (only #{:structure})`) and composition.

**Implementation notes**
1. Replace `test-safely`/`test-throws` with a scenario registry in `kernel.sanity_checks`.
2. Provide adapters (`run-all`, `explain-failures`) that format results similar to Malli reports.
3. Gradually migrate existing functions to thin wrappers around `run-scenarios` for backwards compatibility.

---

## Proposal 30 · Coroutine Derivation Builders (Missionary)

**Current touchpoints**
- `src/kernel/core.cljc:160-360` (derivation) and `524-575` (transaction loop) rely on nested loops + manual try/catch.

**Pain**
- Interleaving validation, trace instrumentation, and error wrapping makes the core loop hard to read and harder to extend with new behaviour.

**Inspiration**
- Missionary `sp/ap` macros (`missionary/src/missionary/core.cljc:230-310`) use `cloroutine.core/cr` to build resumable coroutines with clean syntax.

**Proposed change**
- Introduce a `defstage` macro that mirrors Missionary’s approach: compile stage bodies into coroutines that can `yield` diagnostic events while keeping straight-line code for stage logic.

**Implementation notes**
1. Add `kernel.stage` with `defstage` + runner functions (no async required, but reuse the coroutine trick).
2. Port derivation/transaction stages to the new DSL and capture yielded events for traces/pattern audits.
3. Document how to extend the stage pipeline by composing these coroutines.

---

## Proposal 31 · Pure Event Bus Primitives (Missionary)

**Current touchpoints**
- Ad hoc atoms/vectors collect trace data in `src/kernel/core.cljc` and `kernel.sanity_checks.cljc`.

**Pain**
- There is no standard, pure abstraction for broadcasting transaction events to tooling; each consumer wires its own mutable accumulator.

**Inspiration**
- Missionary’s `dfv`, `mbx`, and `rdv` helpers (`missionary/src/missionary/core.cljc:320-430`) expose clean single-assignment, mailbox, and rendezvous APIs. Their Java backends (`missionary/java/missionary/impl/Mailbox.java`, `Rendezvous.java`) show how persistent vectors/sets guarantee fairness and how cancellation invokes `missionary.Cancelled` on pending takers. Electric’s `missionary_util.cljc` wraps these ports with instrumentation, proving the pattern scales.

**Proposed change**
- Create `kernel.bus` with synchronous mailbox/dataflow helpers inspired by Missionary to funnel kernel events into a pluggable, deterministic bus, optionally wrapping Missionary ports directly for reuse.

**Implementation notes**
1. Implement mailbox/dataflow primitives with pure data structures, mirroring Missionary’s arity conventions, or wrap the Missionary ports when available.
2. Route transaction traces, mutation logs, and sanity checks through the bus instead of bespoke atoms.
3. Expose adapters so LLM agents or dev tools can subscribe/inspect event streams uniformly.

---

## Proposal 32 · Transaction Peephole Optimizer (Electric)

**Current touchpoints**
- Raw op vectors run straight through `kernel.core/->tx` and `apply-tx+effects*` without simplification.

**Pain**
- Redundant sequences (`create`→`delete`, `move` to same slot) waste time and make agent-generated plans noisy.

**Inspiration**
- Electric’s `Expr` protocol and `peephole` method (`hyperfiddle/electric/impl/runtime3.cljc:55-130`) simplify expression trees during compilation.

**Proposed change**
- Model transactions as a tiny AST and run peephole simplifiers before emitting final op maps.

**Implementation notes**
1. Add `kernel.tx.expr` nodes with `simplify` similar to Electric’s `peephole`.
2. Collapse redundant op sequences and merge compatible updates.
3. Insert optimizer into transaction ingestion path.

---

## Proposal 33 · GC-Aware Watchdogs (Electric)

**Current touchpoints**
- Pattern watchers and simulation logs hold onto callbacks indefinitely.

**Pain**
- Without lifecycle hooks, REPL sessions leak observers, skewing diagnostics.

**Inspiration**
- Electric’s `reclaim` helper (`hyperfiddle/electric/impl/runtime3.cljc:23-37`) attaches finalizers/`FinalizationRegistry` tokens to cleanup peers when GC collects them.

**Proposed change**
- Provide GC-backed watchdog registration so dropping a handle auto-unregisters observers.

**Implementation notes**
1. Implement `kernel.watchdog/gc-token` using `Cleaner` (JVM) / `FinalizationRegistry` (JS).
2. Wrap watcher registries to store tokens and auto-prune on finalization.
3. Update tooling to return handles with embedded tokens.

---
---

## Proposal 20 · Intent Grammar as Trie Algebra (Reitit)

**Current touchpoints**
- `src/kernel/intents` (planned planner) and `src/kernel/core.cljc:567-600` – intents keyed by flat keywords.

**Finding**
Reitit compiles path grammars into tries with a tiny algebra (`reitit.trie`, lines 14-198): constructors (`Wild`, `CatchAll`, `Node`) and a compiler protocol. It treats paths as words in a free monoid, normalises them, and emits deterministic matchers.

**Proposal**
Model multi-step intents as words over kernel axes (existence/topology/order/refs). Build `kernel.grammar` with trie nodes mirroring Reitit’s structure so LLM agents and UIs can resolve high-level sequences like `[:select :move :into]` into canonical transactions.

```clojure
(ns kernel.grammar
  (:require [kernel.ops.registry :as registry]))

(defrecord Step [axis payload])
(defrecord Branch [children wilds catch-all result])

(defn compile-intents [intents]
  (reduce grammar/insert (->Branch {} {} {} nil) intents))
```

Trie compilation guarantees prefix-closure, enabling autocompletion and ambiguity detection (conflicting branches) just like Reitit’s conflict resolution.

**Implementation notes**
1. Port the minimal insertion logic (common-prefix splits, wild/catch-all nodes) from `reitit.trie` to operate on `Step` values.
2. Introduce a compiler protocol to emit evaluators (transaction builders, documentation, audits) from the trie.
3. Extend metamorphic audits (Proposal 6) to fail CI when two workflows collapse to the same prefix (mirroring Reitit’s conflict checks).

---

## Proposal 21 · Transactions as Free Monoid (Datascript)

**Current touchpoints**
- `src/kernel/core.cljc:602-633` – imperative loop threads DB and effects manually.
- `src/kernel/sanity_checks.cljc` – tests treat transactions as unstructured lists.

**Finding**
Datascript’s `transact-report` and `transact-add` (`datascript/db.cljc`, lines 1594-1607) fold a transaction into a report record. This is the free monoid on datom primitives: empty transaction is identity, concatenation is associative.

**Proposal**
Make that algebra explicit by wrapping primitives in a `TxOp` record and defining `compose`/`fold` helpers.

```clojure
(defrecord TxOp [apply derive delta])

(def identity-op (->TxOp identity identity (constantly nil)))

(defn compose [a b]
  (->TxOp (comp (:apply b) (:apply a))
          (comp (:derive b) (:derive a))
          (merge-deltas (:delta a) (:delta b))))

(defn fold-tx [ops]
  (reduce compose identity-op ops))
```

`apply-tx+effects*` becomes `(fold-tx (map primitives->txop ops))`, clarifying associativity and enabling algebraic simplifications (e.g., cancelling inverse moves) before touching the DB.

**Implementation notes**
1. Have each primitive (create/place/update/prune/ref ops) return a `TxOp` carrying `apply`, `derive` and delta metadata (ties into Proposal 2’s incremental derive).
2. Property-test monoid laws (identity & associativity) using small generated op sequences, mirroring Datascript’s `transact` coverage.
3. Expose simplifier hooks so planners can perform word-reduction before execution (e.g., `:move` immediately followed by undo becomes `identity-op`).

---

## Proposal 22 · Effects as Endofunctors (Missionary × Ring Async)

**Current touchpoints**
- `src/kernel/effects.cljc` – detects effects but treats execution as ad-hoc.
- `src/kernel/core.cljc:632-633` – simply accumulates effect maps.

**Finding**
Missionary tasks form a monad (Kleisli composition), and Ring’s async handlers accept dual arities (`handler req` or `handler req respond raise`). We can view each effect as an endofunctor on the adapter category: it maps an adapter to another adapter in the same category, optionally producing values.

**Proposal**
Define an `Effect` protocol with synchronous and asynchronous arities, and treat composition as functor composition.

```clojure
(defprotocol Effect
  (fmap [effect adapter]
    [effect adapter respond raise]))

(defrecord Diff [delta]
  Effect
  (fmap [_ adapter]
    (runtime/run-task adapter
      (missionary/sp (adapter/apply-diff! adapter delta)))))
```

Identity effect is the identity functor; composing effects corresponds to function composition in Missionary’s Kleisli category.

**Implementation notes**
1. Implement `Effect` for each effect type (`:view/diff`, `:selection/focus`, etc.) with dual arities to match Ring’s async pattern.
2. Provide composition utilities (`compose-effects`) that satisfy functor laws (identity, associativity), verified via simulator logs (Proposal 10).
3. Document the categorical framing so planners can reason about effect pipelines algebraically (e.g., recognise when effects commute or annihilate).

---
---

## Proposal 34 · Stage Trace Storyboard (Electric)

**Current touchpoints**
- `src/kernel/core.cljc:631-662` – `apply-tx+effects*` builds a simple `:trace` vector of maps per op.
- Debugging relies on printing the vector or diffing DB snapshots by hand.

**Pain**
No notion of stage-level timeline, durations, or hierarchy. Keeping entire DB snapshots in the trace bloats payloads and makes tooling heavy.

**Inspiration**
- Electric’s `contrib.trace3` storyboard (`/Users/alien/Projects/inspo-clones/electric/src/contrib/trace3.cljc:1-200`) stores trace entries in a tiny triple-store with stamps, parents, and pretty values, then renders them as timelines.

**Proposed change**
Introduce `kernel.trace.storyboard` with `with-storyboard` + `push-stage!` helpers. Stage functions emit `{::event .. ::stamp .. ::stage .. ::op-index ..}` entries; `apply-tx+effects*` attaches the storyboard when `:trace?` (or new `:storyboard?`) is true. Provide replay helpers for deterministic regression tests.

**Implementation notes**
1. Start with an atom-backed store (vector of events) mirroring Electric’s event tuples. Validate entries with Malli.
2. Feed the storyboard tap into the new `defstage` macro (Proposal 35) so every stage automatically records enter/leave events.
3. Offer viewer utilities (EDN + DOM) so CLJ/CLJS adapters can reuse the same timeline view.

**Trade-offs**
- Slight per-stage overhead (timestamp + conj); gate via option flags.
- Need to bound log size for long transactions (ring buffer or `:max-events`).
- Replaying timeline requires deterministic derived data; coordinate with Proposal 10’s simulator.

---

## Proposal 35 · Missionary-Style Stage Guards

**Current touchpoints**
- `src/kernel/core.cljc:550-588` – every stage repeats `try`/`catch` boilerplate to wrap exceptions and attach `:why` metadata.

**Pain**
Duplication makes it easy to drift error payloads or forget instrumentation hooks when adding stages.

**Inspiration**
- Missionary’s `attempt`/`absolve` helpers (`/Users/alien/Projects/inspo-clones/missionary/src/missionary/core.cljc:72-115`) lift the body into a thunk and centralise exception handling, while the `holding` macro and Electric’s `wrap-task*` instrumentation (`/Users/alien/Projects/inspo-clones/electric/src/hyperfiddle/electric/impl/missionary_util.cljc:150-230`) show how to bolt on enter/leave hooks without touching the guarded body.

**Proposed change**
Add `kernel.pipeline.guard/defstage` macro. Stage authors write only the happy-path body; the macro injects standard error handling, trace taps, metadata, and forwards to a shared executor akin to Missionary’s `attempt` runner.

**Implementation notes**
1. Place macro in `src/kernel/pipeline/guard.cljc`, export optional `:tap` hook to integrate with Proposal 34’s storyboard.
2. Rewrite existing stages to use `defstage`, then delete the hand-written versions.
3. Update sanity checks to assert every stage exported to `default-pipeline` declares a `:why` keyword (macro enforces this).

**Trade-offs**
- Adds a macro layer in hot code; mitigate with macroexpansion tests and clj-kondo hints.
- Contributors must learn the DSL, but it mirrors Missionary patterns already referenced in docs.

---


## Proposal 37 · Datafy & Nav Bridge (clojure.datafy)

- **Source inspiration**: `/Users/alien/Projects/inspo-clones/clojure/src/clj/clojure/datafy.clj`, REBL datafy tooling.
- **Targets**: `src/kernel/introspect.cljc`, REPL utilities.
- **Thesis**: attach `clojure.core.protocols/Datafiable` + `Navigable` implementations to kernel DB snapshots so inspectors and agents can explore via `datafy/nav` instead of raw map poking.

## Proposal 38 · Zipper Planner Rewrites (clojure.zip)

- **Source inspiration**: `/Users/alien/Projects/inspo-clones/clojure/src/clj/clojure/zip.clj`.
- **Targets**: planner DSL (future `kernel.planner`), docs/ideas/proposal_38.
- **Thesis**: expose zipper-based editing helpers for transaction plans, replacing manual vector surgery with persistent navigation primitives.

## Proposal 39 · Schema LRU & Pull Cache (Datascript)

- **Source inspiration**: `/Users/alien/Projects/inspo-clones/datascript/src/datascript/lru.cljc`, `pull_api.cljc`.
- **Targets**: `kernel.schemas`, planner validation.
- **Thesis**: introduce LRU-backed cached schemas and pull helpers to amortize Malli compilation and ref traversals.

## Proposal 40 · IncSeq Diff Effects (Electric)

- **Source inspiration**: `/Users/alien/Projects/inspo-clones/electric/src/hyperfiddle/incseq/diff_impl.cljc` & `electric_dom3.cljc`.
- **Targets**: `kernel.effects`, adapters.
- **Thesis**: compute preorder diffs via IncSeq and emit normalized `:view/diff` effects, enabling deterministic patching across adapters.

## Proposal 41 · Missionary Storyboard Bus

- **Source inspiration**: Missionary `rdv/mbx` (`missionary/core.cljc:330-413`), Electric `missionary_util.cljc`.
- **Targets**: `apply-tx+effects*` instrumentation, Proposal 34 storyboard.
- **Thesis**: replace ad hoc trace atoms with a Missionary rendezvous bus feeding a storyboard timeline.

## Proposal 42 · Pull Patterns for Refs (Datascript)

- **Source inspiration**: Datascript pull executor (`pull_api.cljc`).
- **Targets**: `kernel.refs`, graph tooling.
- **Thesis**: offer declarative pull DSL over kernel refs with cached pattern compilation, simplifying backlinks and graph queries.

## Proposal 43 · Missionary Simulator Harness

- **Source inspiration**: Electric `missionary_util.cljc`, Missionary `sp/?` macros.
- **Targets**: `docs/ideas/proposal_10`, effect testing.
- **Thesis**: run mutation/effect simulations in Missionary fibers for deterministic async testing.

## Proposal 44 · REPL Path Lenses (Slate)

- **Source inspiration**: Slate `PathInterface` (`packages/slate/src/interfaces/path.ts`).
- **Targets**: `kernel.introspect`, REPL tooling.
- **Thesis**: add path lens helpers mirroring Slate’s API for ergonomic navigation and transforms.

## Proposal 36 · Slate-Style Path Algebra

**Current touchpoints**
- `src/kernel/core.cljc:355-382` – `place*` manually splices vectors and recomputes sibling indices.
- Derived maps hold multiple redundant structures (`:index-of`, `:prev-id-of`, `:next-id-of`).

**Pain**
Structural ops duplicate splice/index math and planners juggle raw IDs + derived lookups with no shared helpers.

**Inspiration**
- Slate’s `PathInterface` (`/Users/alien/Projects/inspo-clones/slate/packages/slate/src/interfaces/path.ts:1-160`) treats editor positions as integer paths with helpers (`ancestors`, `levels`, `transform`) and per-op path transformers.

**Proposed change**
Derive an `:index-path` vector for every node and add `kernel.path` utilities mirroring Slate’s helpers. Rewrite `place*`, `move`, `reorder`, and planners to operate on path algebra (insert/remove/transform) instead of bespoke vector surgery.

**Implementation notes**
1. Extend derivation with DFS index-paths; expose `path/from-id`, `path/ancestors`, `path/transform`.
2. Collapse sibling metadata (`:index-of`, `:prev-id-of`, `:next-id-of`) into path computations to shrink derived payloads.
3. Update tests to assert path transforms stay consistent across move/delete combos.

**Trade-offs**
- Migration needs care: keep old helpers during transition until tests cover new path semantics.
- Index-path derivation adds upfront work, but removes several redundant caches in steady state.
- Requires adapters to respect canonical vector ordering (already required by kernel API).

---

## New proposals (2025-09-29)

- **Proposal 45 · Specter-Style Navigators** — factor navigation helpers into composable navigators inspired by Specter’s `defnav` macros so planners can compose tree queries without bespoke functions (`docs/ideas/proposal_45_specter_nav_registry.md`).
- **Proposal 46 · Meander Tx Rewrites** — express normalization rules as Meander rewrite clauses instead of hand-rolled loops, enabling declarative transaction simplification (`docs/ideas/proposal_46_meander_tx_rewrite.md`).
- **Proposal 47 · Portal Inspector Bridge** — wrap kernel snapshots with Portal viewers to replace ad hoc REPL printers with structured inspectors (`docs/ideas/proposal_47_portal_inspector_bridge.md`).
- **Proposal 48 · Onyx-Style Pipeline Graph** — model stages as catalog/workflow data, unlocking branching and visualization similar to Onyx jobs (`docs/ideas/proposal_48_onyx_op_pipeline_graph.md`).
- **Proposal 49 · Adapton Derivation Graph** — record dependency edges between derived artefacts for incremental dirtying and richer explanations (`docs/ideas/proposal_49_adapton_derivation_graph.md`).
- **Proposal 50 · Javelin Formula Cells** — represent derived fields as Javelin cells to get automatic invalidation and lazy recomputation (`docs/ideas/proposal_50_javelin_derived_cells.md`).
- **Proposal 51 · Rewrite-clj Splicing Toolkit** — extract splice primitives for tree surgery, mirroring rewrite-clj’s zipper APIs to shrink `place*`/`prune*` (`docs/ideas/proposal_51_rewrite_clj_splice_ops.md`).
- **Proposal 52 · Malli Transformers** — attach transformer interceptors to op schemas so normalization/coercion happens centrally (`docs/ideas/proposal_52_malli_transform_pipeline.md`).
- **Proposal 53 · Core.logic Invariants** — rewrite invariant checks as logic relations that yield counterexamples instead of imperative loops (`docs/ideas/proposal_53_core_logic_invariants.md`).
- **Proposal 54 · Integrant-Keyed Stages** — manage pipeline configuration through Integrant keys, supporting environment-specific stage stacks (`docs/ideas/proposal_54_integrant_stage_config.md`).

> Note: Proposal 1 (“Modular Derivation Pass Registry”) graduated into baseline and the standalone file was retired to avoid duplication.

## Additional proposals (2025-09-29)

- **Proposal 55 · Tree-Sitter Style Incremental Delta Windows** — capture changed ranges per op to focus derivation/effects on touched spans (`docs/ideas/proposal_55_tree_sitter_delta.md`).
- **Proposal 56 · Rope-Based Text Nodes à la Xi Editor** — adopt persistent ropes for large text props and delta-driven updates (`docs/ideas/proposal_56_xi_rope_delta.md`).
- **Proposal 57 · Keyed Morphdom-Style View Effects** — emit keyed diff instructions so adapters can reconcile DOM/state surgically (`docs/ideas/proposal_57_morphdom_keyed_effects.md`).
- **Proposal 58 · Clerk-Style Analysis Cache for Planner Intelligence** — cache intent/plan analysis graphs to avoid recomputing dependencies (`docs/ideas/proposal_58_clerk_analysis_cache.md`).
- **Proposal 59 · SCI Sandbox for Intent Plugins** — execute user plugins inside SCI with explicit capability lists (`docs/ideas/proposal_59_sci_plugin_sandbox.md`).
- **Proposal 60 · Meander Rewrite Pass for Sugar Ops** — declare sugar intent lowering with `m/rewrite` tables instead of hand-written `let` chains (`docs/ideas/proposal_60_meander_intent_lowering.md`).
- **Proposal 61 · Specter-Powered Invariant Playbook** — express integrity checks via Specter navigators for clearer violation reports (`docs/ideas/proposal_61_specter_invariant_playbook.md`).
- **Proposal 62 · Core.match Patterns for Position Specs** — replace the `pos->index` cond tower with explicit pattern clauses (`docs/ideas/proposal_62_core_match_pos_patterns.md`).
- **Proposal 63 · Atomic Op Builders for Planner Ergonomics** — auto-generate constructor helpers for every op to cut planner boilerplate (`docs/ideas/proposal_63_atomic_op_builders.md`).
- **Proposal 64 · Consequence Flow Combinators for Multi-Step Plans** — model multi-step intent flows as data, inspired by Athens’ composite ops (`docs/ideas/proposal_64_consequence_flows.md`).
- **Proposal 65 · Internal Representation → Ops Transducer** — convert nested IR into canonical op sequences via a shared pipeline (`docs/ideas/proposal_65_ir_to_ops_transducer.md`).

## Macro proposals (2025-09-29)

- **Proposal 66 · `defpass` Macro for Derivation Registry** — generate and register derivation passes with declarative metadata instead of hand-maintained vectors (`docs/ideas/proposal_66_defpass_macro.md`).
- **Proposal 67 · `defsanity` Macro for Scenario Deck** — declare sanity checks as single forms that auto-register with the scenario matrix (`docs/ideas/proposal_67_defsanity_macro.md`).
- **Proposal 68 · `defdeckrule` Macro for Invariant Deck** — wrap invariant definitions in a registry-aware macro that enforces consistent findings (`docs/ideas/proposal_68_defdeckrule_macro.md`).
- **Proposal 69 · `defresponse` Builders for Envelope Functions** — cut boilerplate in response constructors by generating builders with validation and docs (`docs/ideas/proposal_69_defresponse_builders.md`).
- **Proposal 70 · `defpeephole` DSL for Transaction Normalization** — encode normalization rules declaratively and register them with metadata for instrumentation (`docs/ideas/proposal_70_defpeephole_rules.md`).
- **Proposal 71 · `deftracefn` Macro for Storyboard-Integrated Functions** — standardize tracing/error envelopes for stages and primitives via a macro wrapper (`docs/ideas/proposal_71_deftracefn_storyboard.md`).
- **Proposal 72 · `defprimitive` Macro for Core Operation Registration** — define primitives once and auto-register handlers, schemas, and instrumentation (`docs/ideas/proposal_72_defprimitive_registry.md`).
- **Proposal 73 · `defeffect` Macro for Effect Detection Registry** — build a registry of effect detectors with consistent payloads and toggles (`docs/ideas/proposal_73_defeffect_registry.md`).
- **Proposal 74 · `deferror` Macro for Uniform Kernel Exceptions** — eliminate ad hoc `ex-info` usage with structured, registered error definitions (`docs/ideas/proposal_74_deferror_macro.md`).
- **Proposal 75 · `defschema` Macro for Malli Registry Entries** — replace the literal schema map with macro-registered entries that carry docs and union metadata (`docs/ideas/proposal_75_defschema_registry.md`).

## Additional proposals (2025-09-29 cont.)

- **Proposal 76 · Kakoune-Style Selection Intents** — make selections the primary lowering artefact for move/indent/outdent, mirroring Kakoune’s range-first model (`docs/ideas/proposal_76_kakoune_selection_intents.md`).
- **Proposal 77 · Bevy-Style Schedule Gates** — turn the pipeline vector into a stage schedule with per-stage `run?` predicates (`docs/ideas/proposal_77_bevy_schedule_gates.md`).
- **Proposal 78 · Salsa Query Groups** — declare cached derived queries with explicit input slots so we can invalidate lazily (`docs/ideas/proposal_78_salsa_derived_queries.md`).
- **Proposal 79 · Expresso Rule Declarations** — express invariants as declarative rules with guards, returning counterexample bindings (`docs/ideas/proposal_79_expresso_rule_declarations.md`).
- **Proposal 80 · HoneySQL Intent Builder** — replace ad-hoc tx vectors with a clause DSL that is easy to extend (`docs/ideas/proposal_80_honeysql_intent_builder.md`).
- **Proposal 81 · Aero Profiles** — centralise instrumentation flags via profile-aware EDN config (`docs/ideas/proposal_81_aero_profiled_configs.md`).
- **Proposal 82 · Core.Typed Guardrails** — add optional static types for kernel primitives to catch contract violations earlier (`docs/ideas/proposal_82_coretyped_guardrails.md`).
- **Proposal 83 · Vlojure Visual EDN** — render tx traces as vedn tokens for human and LLM comprehension (`docs/ideas/proposal_83_vlojure_visual_edn.md`).
- **Proposal 84 · Neovim Extmark Anchors** — maintain stable cursor anchors that survive text splices (`docs/ideas/proposal_84_neovim_extmark_anchors.md`).
- **Proposal 85 · MCP Tool Registry** — unify extension registration and validation through an MCP-inspired multimethod hub (`docs/ideas/proposal_85_mcp_tool_registry.md`).
