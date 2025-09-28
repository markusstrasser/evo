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

Each proposal builds on proven patterns from the inspiration repos and references the exact Evolver locations they would reshape.
