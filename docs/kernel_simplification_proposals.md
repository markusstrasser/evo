# Kernel Simplification Proposals (2025-09-28)

This report summarizes four concrete refactoring opportunities to reduce kernel complexity while preserving behaviour. Each proposal includes context from the current code base, inspiration drawn from `/Users/alien/Projects/inspo-clones/`, and a before/after sketch to illustrate the change. Only documentation is modified; production code remains untouched.

## Proposal 1: Modular Derivation Pass Registry

- **Current friction** (`src/kernel/core.cljc:178-246`): `derive-core` and `derive-dx` are monolithic functions that manually compose every derived field. Adding or reordering a pass requires editing large functions and remembering invariants by hand.
- **Inspiration**: `pathom3/src/main/com/wsscode/pathom3/plugin.cljc` demonstrates a tiny plugin engine that composes registries through `register-plugin` and `compile-extensions`.
- **Idea**: Introduce a `kernel.derive` registry where each derived attribute declares its dependencies and builder. The registry composes passes automatically, so extending Tier-A/Tier-B data no longer touches shared code.

**Before**
```clojure
;; src/kernel/core.cljc
(defn derive-full [db]
  (let [core (derive-core db)]
    (cond-> (assoc db :derived (derive-dx db core))
      (not (:version db)) (assoc :version 1))))
```

**After (sketch)**
```clojure
;; src/kernel/derive.cljc
(defonce ^:private passes (atom []))
(defmacro defpass [k {:keys [requires]} & body]
  `(swap! passes conj {:key ~k :requires ~requires :builder (fn [db derived#] ~@body)}))

(defn run-passes [db]
  (reduce (fn [acc {:keys [builder]}]
            (builder db acc))
          {:parent-id-of {} :child-ids-of {}}
          (topo/sort @passes)))

;; registering passes near their domains keeps code local
(defpass :order-index {:requires #{:parent-id-of :child-ids-of}}
  (assoc derived# :order-index-of (build-order-index derived#)))
```

- **Impact**: Cuts repeated plumbing, makes derived additions opt-in per feature, and mirrors the plugin ergonomics proven in Pathom. Future passes (e.g., selection metadata) become small self-contained forms instead of edits to a 200+ line function.

## Proposal 2: Incremental Derived Updates Per Operation

- **Current friction** (`src/kernel/core.cljc:470-515`): `apply-tx+effects*` re-runs the full derivation (`derive`) after every op, even for single-node updates, leading to unnecessary work and tight coupling between ops and derivation.
- **Inspiration**: `datascript/src/datascript/db.cljc` uses `with-datom` to update indexes lazily per datom, avoiding full recompute while keeping index code centralized.
- **Idea**: Track small derived deltas per op (`kernel.derive/update-derived`) that update only touched parents, siblings, and caches. The bulk re-derive remains as a fallback for debugging, but the hot path becomes incremental.

**Before**
```clojure
;; src/kernel/core.cljc
(let [d1 (-> d (apply-op op) derive)]
  ...)
```

**After (sketch)**
```clojure
;; src/kernel/core.cljc
(let [{:keys [db' derived-delta]} (apply-op d op)
      d1 (kernel.derive/apply-delta db' derived-delta)]
  ...)
```
```clojure
;; kernel.derive/apply-delta (inspired by datascript's with-datom)
(defn apply-delta [db {:keys [touch-nodes touch-parents refs]}]
  (-> db
      (update :derived update-parent-indexes touch-parents)
      (update :derived update-order-caches touch-parents)
      (update :derived prune-orphans touch-nodes)))
```

- **Impact**: Lifts re-derive cost from O(tree) to O(touched-subtree), matching Datascript's proven index maintenance model. This trims per-op latency and makes the kernel more "mechanical" for LLM planners.

## Proposal 3: `defop` Macro for Composite Operations

- **Current friction** (`src/kernel/sugar_ops.cljc:9-59` + `src/kernel/schemas.cljc:45-101`): Sugar ops manually assert inputs, re-state defaults, and rely on a separate Malli registry. Adding a new intent requires editing both files and duplicating validation logic.
- **Inspiration**: `pathom3/src/main/com/wsscode/pathom3/connect/operation.cljc` shows how `defresolver` infers inputs/outputs, attaches metadata, and registers the resolver in one place.
- **Idea**: Provide a macro (`kernel.intents/defop`) that registers the Malli schema, default map, and `apply-op` method together. The macro infers required keys from destructuring and emits the validation spec automatically.

**Before**
```clojure
(defmethod K/apply-op :insert
  [db {:keys [id parent-id type props pos] :or {type :div props {}}}]
  (assert id "insert: :id required")
  ...)
```

**After (sketch)**
```clojure
(kernel.intents/defop insert
  {:requires [id parent-id]
   :defaults {:type :div :props {}}
   :doc "Create & attach a node in one op."}
  (-> db
      (K/create-node* {:id id :type type :props props})
      (K/place* {:id id :parent-id parent-id :pos (or pos :last)})))
```
```clojure
;; defop macro handles:
;; 1. Malli schema registration
;; 2. apply-op defmethod wiring
;; 3. Docstring + tracing metadata
```

- **Impact**: Single-source-of-truth for op definition reduces copy/paste mistakes, automatically documents ops, and mirrors Pathom's ergonomic intent macros.

## Proposal 4: Structured Diff Effects via IncSeq

- **Current friction** (`src/kernel/effects.cljc:5-13`): Effect detection only emits a hard-coded scroll effect and provides no structured view diff, pushing adapters to recalculate DOM changes themselves.
- **Inspiration**: `electric/src/hyperfiddle/incseq/diff_impl.cljc` encodes minimal sequence deltas (`{:grow … :permutation … :change …}`) that adapters can apply deterministically.
- **Idea**: Replace bespoke `:view/scroll-into-view` emission with a diff builder that compares `:derived` fields before/after an op and emits a normalized delta struct. Adapters can pattern-match to update DOM, TUI, or tests without extra logic.

**Before**
```clojure
(defn detect [prev next op-index op]
  (cond-> []
    (= (:op op) :insert)
    (conj {:effect :view/scroll-into-view ...})))
```

**After (sketch)**
```clojure
(defn detect [prev next op-index _]
  (let [diff (kernel.diff/seq-diff (preorder prev) (preorder next))]
    (cond-> []
      (not (kernel.diff/empty? diff))
      (conj {:effect :view/diff
             :delta diff
             :cause {:op-index op-index}})))
```
```clojure
;; kernel.diff/seq-diff adapts incseq's `empty-diff`/`combine` primitives
;; to return {:grow :shrink :permutation :change}, enabling adapter re-use.
```

- **Impact**: Promotes deterministic, minimal UI patches with zero hand-written adapter heuristics. The incseq model already powers Electric's UI runtime, so we can borrow a field-tested representation instead of inventing our own.

---

These proposals aim to carve clearer extension seams, reduce re-derivation overhead, and delegate routine boilerplate to macros. Each draws on production-grade patterns from the reference projects, minimizing risk while raising the kernel's ergonomics for both humans and LLM agents.

## Proposal 5: Fiber-Orchestrated Effect Runner

- **Current friction** (`src/kernel/core.cljc:605-656`, `src/kernel/effects.cljc:5-13`): `apply-tx+effects*` hands back raw effect maps and leaves sequencing, cancellation, and error handling to every caller. Each adapter has to reinvent the same orchestration loop, so the semantics of retries or batching are scattered and impossible to enforce centrally.
- **Inspiration**: `missionary/src/missionary/core.cljc` (notably the `sp`/`ap` macros and `join`/`race` helpers at lines 268-343) packages deterministic structured concurrency in tiny wrappers.
- **Idea**: Wrap `:effects` into `missionary` tasks once and expose `kernel.runtime/run-effects` that composes them via `m/join`. Effect handlers become pluggable fns that return tasks, so adapters only pick executors (`m/blk`, `m/cpu`).

**Before**
```clojure
;; src/kernel/core.cljc
(let [es (effects/detect d d1 i op)]
  (recur (inc i) d1 (into effs es)))
```

**After (sketch)**
```clojure
;; src/kernel/runtime.cljc
(defn effect-task [{:keys [effect] :as e}]
  (case effect
    :view/scroll-into-view (m/sp (adapter/scroll! e))
    :view/diff             (m/sp (adapter/apply-diff! e))
    (m/sp (adapter/custom! e))))

(defn run-effects [effects]
  (m/? (apply m/join vector (map effect-task effects))))

;; src/kernel/core.cljc
(let [es (effects/detect d d1 i op)]
  (kernel.runtime/run-effects es)
  (recur (inc i) d1 effs))
```

- **Impact**: Centralises cancellation behaviour, gives us declarative batching, and makes async adapters a drop-in (missionary already ships mailbox/dataflow primitives we can reuse without custom thread code).

## Proposal 6: Transaction Pattern Watchers for Regression Detection

- **Current friction** (`src/kernel/sanity_checks.cljc:312-472`): `run-all` aggregates boolean checks but cannot describe how a pathological tx unfolded. Debugging multi-op regressions still means printing traces and eyeballing sequences.
- **Inspiration**: `re-frame-10x/src/day8/re_frame_10x/tools/metamorphic.cljc` builds metamorphic pattern detectors with small predicate functions and `id-between-xf` transducers to narrate epochs.
- **Idea**: Ship a `kernel.audit` namespace that lets us define reusable transaction motifs (`defpattern`) and run them over the tx log, reporting human-readable narratives ("insert followed by orphaned reorder"), not just pass/fail.

**Before**
```clojure
;; src/kernel/sanity_checks.cljc
(let [result (apply-tx+effects* ...)]
  {:passed? (and core-works? sugar-works? ...) ...})
```

**After (sketch)**
```clojure
;; src/kernel/audit.cljc
(defpattern orphaned-reorder
  (begin :insert)
  (followed-by :reorder #(= (:parent-id %) (:dst %)))
  (ends-when :delete))

(defn audit [trace]
  (->> trace
       (metamorphic/run [orphaned-reorder move-bounce])
       (map metamorphic/summarise-match)))

;; sanity_checks can now call (audit (:trace result)) and surface structured stories.
```

- **Impact**: Gives LLM or human operators higher-level failure descriptions, shrinking time-to-diagnose and encouraging command-level regression tests.

## Proposal 7: Declarative Transaction Pipelines

- **Current friction** (`src/kernel/core.cljc:605-656`): validation, op execution, invariant checking, and effect detection are interleaved inside a single `loop`. Extending the flow (e.g., to add metrics or forks) means touching the control flow directly.
- **Inspiration**: `clojure-mcp/tools/unified_clojure_edit/pipeline.clj` stages editing via `thread-ctx`, letting each step short-circuit cleanly while keeping the pipeline list declarative.
- **Idea**: Represent tx evaluation as a vector of stage fns that operate on a shared context map. Stages can be toggled (e.g., skip invariants in production) or extended (add tracing) without rewriting the loop.

**Before**
```clojure
(loop [i 0, d (derive db), effs []]
  (if (= i (count ops))
    {:db d :effects effs}
    (let [op (nth ops i)]
      (S/validate-op! op)
      (let [d1 (-> d (apply-op op) derive)
            _ (when assert? (inv/check-invariants d1))
            es (effects/detect d d1 i op)]
        (recur (inc i) d1 (into effs es))))))
```

**After (sketch)**
```clojure
(def tx-stages
  [validate-op
   apply-op-and-derive
   maybe-check-invariants
   detect-effects])

(defn run-stage [ctx stage]
  (if (::error ctx)
    ctx
    (stage ctx)))

(defn apply-tx+effects* [db tx opts]
  (reduce run-stage {:ops (->tx tx) :cursor 0 :db db :opts opts}
          tx-stages))
```

- **Impact**: Easier feature toggles (skip invariants in perf-critical contexts), simpler testing (stage-level unit tests), and alignment with the pipeline mental model used in the MCP repo.

## Proposal 8: Data-First Intent Routing

- **Current friction** (`src/kernel/core.cljc:567-590`, `src/kernel/schemas.cljc:45-101`): multimethod dispatch requires side-effecting `require`s to register ops and scatters metadata across namespaces. Listing available ops or generating docs means walking code, not data.
- **Inspiration**: `reitit/modules/reitit-core/src/reitit/core.cljc` builds routers from pure data and compiles efficient lookup tables (`linear-router`, `lookup-router`).
- **Idea**: Store op definitions in a data table (`[{::op :insert ::handler insert-handler ::schema ::insert-op ...}]`), compile it once into (1) a fast name→handler map and (2) a Malli union schema. Loading becomes order-independent and introspection/documentation is trivial.

**Before**
```clojure
(defmulti apply-op (fn [_db op] (:op op)))
(defmethod apply-op :insert [db op] ...)
```

**After (sketch)**
```clojure
;; src/kernel/op_registry.cljc
(def ops
  [{:op :insert :schema ::schemas/insert-op :handler insert-handler}
   {:op :move   :schema ::schemas/move-op   :handler move-handler}])

(def compiled
  (-> ops
      (reitit-like/compile {:lookup-key :op})
      (assoc :schema (malli/->union (map :schema ops)))))

(defn apply-op [db op]
  (if-let [handler (get-in compiled [:handlers (:op op)])]
    (handler db op)
    (throw (unknown-op ex-data))))
```

- **Impact**: Deterministic startup (no load-order traps), automatic docs (`map :op ops`), and shared metadata for tooling (LLMs can introspect available ops without reflection).

## Proposal 9: Schema-Driven State Coercion

- **Current friction** (`src/kernel/core.cljc:210-259`, `src/kernel/core.cljc:323-356`): we hand-roll conversions when deriving indexes (`derive-dx` recomputes `preorder`, `position-of`, etc.) and when pruning nodes (`prune*` walks sets to normalise). Migrating persisted logs or accepting user-supplied IDs means duplicating coercion logic.
- **Inspiration**: `malli/transform.cljc` ships bidirectional transformers (`-string->uuid`, `-number->long`, etc.) that normalise shapes declaratively.
- **Idea**: Define a `::db/normalizer` transformer that guarantees canonical shapes (`:child-ids/by-parent` vectors, keyword props, UUID ids). Run it at tx boundaries so `derive-core` and `prune*` can assume invariant input and drop defensive conversions.

**Before**
```clojure
(defn prune* [db pred]
  (let [ids (set (keys (:nodes db)))
        victims (if-let [derived (:derived db)] ...)]
    (reduce (fn [d id]
              (-> d
                  (update :nodes dissoc id)
                  (update :child-ids/by-parent dissoc id)))
            db victims))
```

**After (sketch)**
```clojure
;; src/kernel/normalize.cljc
(def db-transformer
  (mt/transformer
    (mt/-string->keyword)
    (mt/-string->uuid)
    (mt/-number->long)))

(defn normalize-db [db]
  (m/transform db-transformer schemas/db-schema db))

;; prune* simply assumes vectors + keywords after (normalize-db db).
```

- **Impact**: Removes ad-hoc coercion branches, strengthens backwards-compatibility for persisted tx logs, and unlocks cheap import/export (incoming JSON can flow through the transformer before hitting the kernel).
