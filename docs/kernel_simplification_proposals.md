# Kernel Simplification Proposals (2025-09-28)

This document collects design proposals aimed at shrinking complexity in the Evolver kernel while keeping behaviour intact. Each section lists the current touchpoints in this repo, the idea we would port from `/Users/alien/Projects/inspo-clones/`, before/after sketches, and concrete change bullets. No production code has been modified—this is a planning document only.

---

## Proposal 1 · Modular Derivation Pass Registry

**Current touchpoints**
- `src/kernel/core.cljc:178-257` – `derive-core` and `derive-dx` manually accumulate Tier-A/Tier-B maps.
- `src/kernel/core.cljc:602-633` – `apply-tx+effects*` calls `derive` on every loop iteration.

**Pain**
New derived fields require editing large functions, threading temporary maps, and remembering ordering constraints by hand.

**Inspiration**
- Pathom plugin system (`/Users/alien/Projects/inspo-clones/pathom3/src/main/com/wsscode/pathom3/plugin.cljc:15-125`).

**Proposed change**
Create `src/kernel/derive.cljc` with a registry of pass descriptors (required keys + builder). Each pass registers itself via `defpass`, and `derive-full` becomes `run-passes` over a topologically sorted list.

```clojure
;; After (sketch)
(ns kernel.derive
  (:require [clojure.set :as set]))

(defonce ^:private passes (atom {}))

(defmacro defpass [k {:keys [requires doc]} & body]
  `(swap! passes assoc ~k {:requires ~requires :doc ~doc
                           :builder (fn [db derived#] ~@body)}))

(defn run-passes [db]
  (let [order (topo-sort @passes)]
    (reduce (fn [derived {:keys [builder]}]
              (builder db derived))
            {:derived {}}
            order)))
```
`kernel.core/*derive-pass*` would call `(derive/run-passes db)` and cache the result.

**Expected benefits**
- Adding a new derived artefact is a local, ~10 line change.
- Testability: each pass can be property-tested in isolation.
- Tooling can inspect `@passes` for documentation or introspection UIs.

**Trade-offs**
- Slight runtime overhead from indirection (map lookups, dependency sort) versus hard-coded functions; mitigated by caching the sorted pass order.
- Requires disciplined naming of pass keys; an incorrect dependency set could lead to subtle runtime failures (needs validations in CI).

**Implementation notes**
1. Lift existing Tier-A/Tier-B bodies into `defpass` forms (e.g., `:parent-id-of`, `:preorder`).
2. Add topo-sort validation so missing prerequisites fail fast.
3. Update sanity checks to require the new namespace.

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

## Proposal 7 · Declarative Transaction Pipeline

**Current touchpoints**
- `src/kernel/core.cljc:602-633` – validation, op execution, invariants, and effects interleaved in one loop.

**Pain**
Adding cross-cutting stages (metrics, auditing) means editing the core loop directly.

**Inspiration**
- MCP editing pipeline (`/Users/alien/Projects/inspo-clones/clojure-mcp/tools/unified_clojure_edit/pipeline.clj:15-120`).

**Proposed change**
Represent evaluation as an ordered vector of stage fns working on a context map:

```clojure
(def tx-stages
  [stage/derive-input
   stage/validate-op
   stage/apply-op
   stage/check-invariants
   stage/detect-effects])

(defn run-stage [ctx stage]
  (if (::error ctx) ctx (stage ctx)))

(defn apply-tx+effects* [db tx opts]
  (reduce run-stage {::db db ::ops (->tx tx) ::cursor 0 ::opts opts}
          tx-stages))
```

**Expected benefits**
- Toggleable stages (skip invariants in prod, enable auditing in dev).
- Unit tests per stage rather than per loop branch.

**Implementation notes**
1. Introduce `kernel.tx.context` namespace to define context keys.
2. Stage fns return updated context or `assoc ::error ...` to short-circuit.
3. Keep backwards-compatible return shape by reading context at the end.

---

## Proposal 8 · Data-First Op Registry (Reitit)

**Current touchpoints**
- `src/kernel/core.cljc:567-600` – multimethod dispatch for `apply-op`.
- `src/kernel/schemas.cljc:45-112` – schema union built manually.

**Pain**
Order-dependent registration; discovering available ops requires scanning code.

**Inspiration**
- Reitit router compilation (`/Users/alien/Projects/inspo-clones/reitit/modules/reitit-core/src/reitit/core.cljc:1-160`).

**Proposed change**
Store op definitions in data:

```clojure
(def ops
  [{:op :create-node :schema ::schemas/create-node-op :handler create-node*}
   {:op :insert      :schema ::schemas/insert-op      :handler insert* :axes #{:existence :order}}])

(def compiled (ops/compile ops))

(defn apply-op [db op]
  (if-let [handler (get-in compiled [:handlers (:op op)])]
    (handler db op)
    (throw (unknown-op (:op op)))))
```

**Expected benefits**
- Load-order independence (data evaluated once at startup).
- Introspection APIs (list ops, axes, docs) for tooling.

**Implementation notes**
1. Build `ops/compile` similar to `reitit.impl/fast-map` (fast keyword lookup).
2. Auto-generate Malli union via `(malli/->union (map :schema ops))`.
3. Adjust sugar ops to register themselves by conj’ing into the registry.

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

`apply-op` and the new registry (Proposal 8) check `:requires` axes before execution. Invariants can ensure that after a tx touching `:axis/topology`, derived order maps are recomputed.

**Implementation notes**
1. Provide `ops.meta/find-derived` similar to Integrant’s `find-derived` for axis queries.
2. Expose `ops.meta/describe` for tooling and docs.
3. Extend sanity checks to assert that each op has at least one axis annotation.

---

## Proposal 13 · Map Transform Helpers (Medley)

**Current touchpoints**
- `src/kernel/core.cljc:210-257` – verbose `for`/`reduce-kv` loops building derived maps.
- `src/kernel/core.cljc:323-356` – manual `reduce` for pruning children.

**Pain**
Hand-written reductions obscure intent and duplicate transient logic.

**Inspiration**
- Medley map helpers (`/Users/alien/Projects/inspo-clones/medley/src/medley/core.cljc:1-160`).

**Proposed change**
Import Medley (already pure, CLJC) or vendor the needed fns. Rewrite derived builders:

```clojure
(let [child-ids-of (-> adj (medley/map-vals vec) (medley/assoc-some ROOT []))
      index-of     (medley/reduce-map ...)]
  ...)
```

Similarly, `prune*` can use `medley/filter-keys` and `medley/map-kv-vals` instead of nested loops.

**Implementation notes**
1. Add Medley dependency or copy selected helpers with attribution.
2. Refactor derived functions step-by-step, ensuring existing tests still pass.
3. Update docstrings to explain transformations in terms of map utilities.

---

## Proposal 14 · Rewrite-CLJ Planner Rewriters

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
- `src/kernel/core.cljc:602-633` – single loop responsible for every concern.

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

## Proposal 16 · Response Builders & Intent DSL (Ring)

**Current touchpoints**
- `src/kernel/core.cljc:567-600` (primitive ops) and planned high-level intents in `src/evolver/intents`.
- No consolidated helpers for returning standard intent responses (success, conflict, redirect).

**Finding**
Ring’s `ring.util.response` (`/Users/alien/Projects/inspo-clones/ring/ring-core/src/ring/util/response.clj`) offers tiny, composable builders (`response`, `status`, `header`, `redirect`). These serve as data-first constructors with optional convenience defaults.

**Proposal**
Introduce `kernel.intent.response` exposing helpers like `intent/success`, `intent/conflict`, `intent/redirect`. Intent handlers emit standard payloads which adapters interpret consistently (render, highlight error, etc.). Build on top of the op registry (Proposal 8) and binding DSL (Proposal 11).

```clojure
(ns kernel.intent.response)

(defn success [tx & {:keys [effects doc] :or {effects []}}]
  {:status :ok :tx tx :effects effects :doc doc})

(defn conflict [doc]
  {:status :conflict :doc doc})

(defn redirect [intent target-id]
  {:status :redirect :intent intent :target target-id})
```

Adapters (CLI, UI, LLM) can branch on `:status`. Transaction planners would compose these builders just like Ring routes layer `response` helpers.

**Expected benefits**
- Consistent return shape for all intents.
- Documentation can be auto-generated from builder usage.
- Simplifies agent/tool scaffolding (intent results are predictable).

**Implementation notes**
1. Audit existing intent handlers to map their ad-hoc return shapes to new builders.
2. Provide Malli schema for the response map.
3. Extend sanity checks to ensure every intent returns a recognized status.

**Trade-offs**
- Canonical status codes might feel constraining for app-specific adapters (e.g., game engines needing extra context); allow optional `:meta` payloads per client.

---

## Proposal 17 · Transaction Middleware Inspired by Ring’s Cookie/Session Layers

**Current touchpoints**
- `src/kernel/core.cljc:602-633` – no separation between request mutation (reading derived data) and response mutation (writing effects).
- `src/kernel/sanity_checks.cljc` – manual effect assertions.

**Finding**
Ring middleware (e.g., `ring.middleware.params`, `cookies`, `session`) cleanly separates request enrichment and response finalization. Each middleware has `*-request` and `*-response` helpers, and `wrap-*` composes them.

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

This complements Proposal 7 (pipeline) by ensuring each stage has request/response symmetry.

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
