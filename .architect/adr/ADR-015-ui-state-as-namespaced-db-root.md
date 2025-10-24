# ADR-015: UI State as Namespaced DB Root

## Status
Accepted (2025-10-23)

## Problem
Where should ephemeral UI state (selection, collapsed nodes, zoom stack) and undo/redo history live in an event-sourced architecture? Initial proposals suggested separating UI state into a separate `app-state` atom wrapper, but this fights the event-sourced kernel's single-value flow.

## Context

**Existing Pattern**: `:derived` namespace at DB root
```clojure
{:nodes {}
 :children-by-parent {}
 :roots #{}
 :derived {:parent-of {}    ;; Computed indexes
           :index-of {}
           :prev-id-of {}}}
```

**Three candidate approaches**:
1. Separate `app-state` atom wrapping kernel DB (external proposals)
2. UI state scattered across component local state (implicit)
3. UI state as namespaced keys at DB root (matches `:derived` pattern)

## Constraints
- Pure event-sourced kernel with three core ops
- Single DB value flowing through transaction pipeline
- No dynamic vars, minimal atoms (from project philosophy)
- REPL-friendly: all state inspectable
- Plugins manage high-level concerns via pure functions

## Decision

**Store all state—core, derived, and ephemeral UI—as namespaced keys at DB root.**

```clojure
{:nodes {}              ;; Core: managed by three-op kernel
 :children-by-parent {} ;; Core: managed by three-op kernel
 :roots #{}             ;; Core: managed by three-op kernel
 :derived {...}         ;; Computed: derived indexes (core/db.cljc)
 :selection #{...}      ;; Ephemeral: selection plugin
 :ui {:collapsed #{...} ;; Ephemeral: UI plugin
      :zoom-stack [...]}}
```

### Responsibilities

**Core data** (`:nodes`, `:children-by-parent`, `:roots`)
- Modified ONLY by three core ops
- Managed by `core/ops.cljc` and `core/interpret.cljc`

**Derived data** (`:derived`)
- Computed FROM core data
- Recomputed after every core op
- Managed by `core/db.cljc` derive function

**Ephemeral UI state** (`:selection`, `:ui`)
- Managed BY plugins as namespaced state
- Modified through pure functions
- Flows through same transaction pipeline

**Undo/Redo history**
- Operates on entire DB value (includes all namespaces)
- Managed by `core/history.cljc`
- Records/restores complete snapshots

## Rationale

### Why This Over Separate App State?

**Single source of truth**: Event sourcing works on a unified DB value, not bifurcated state.

```clojure
;; ❌ Fighting event sourcing (external proposal pattern)
(atom {:doc kernel-DB       ;; Three-op DB
       :selection #{...}})  ;; Separate UI state

;; ✅ Embracing event sourcing (our pattern)
(def DB {:nodes {} :derived {} :selection #{}})
;; Entire DB flows through transaction pipeline
```

**Undo/redo consistency**: Undoing a structural edit automatically restores selection state.

```clojure
;; With unified DB:
(defn undo [history current-DB]
  {:db (get-in history [:past 0 :db])   ;; Restores ALL state
   :history (update history :past rest)})

;; Selection is automatically correct after undo!
```

**REPL observability**: Single value to inspect.

```clojure
;; One place to look:
@DB-state
;; => {:nodes {...} :derived {...} :selection #{:a :b}}

;; vs. hunting multiple atoms:
@app-state  ;; UI state
@kernel-DB  ;; Core state
```

**Plugin consistency**: Matches `:derived` pattern.

```clojure
;; Derived state: computed FROM core data
(defn derive [DB]
  (assoc DB :derived (compute-indexes DB)))

;; Selection state: managed BY plugin
(defn select-block [DB id]
  (update DB :selection (fnil conj #{}) id))

;; Both are namespaced at DB root
```

### Why Plugins for UI State?

UI state doesn't compile to core ops—it's managed directly:

```clojure
;; Structural editing plugin: compiles intents → core ops
(defmethod compile-intent :indent [DB {:keys [id]}]
  [{:op :place :id id :under sib :at :last}])  ;; → core op

;; Selection plugin: manages :selection namespace directly
(defn select-block [DB id]
  (update DB :selection conj id))  ;; → direct update

;; Both are plugins, different responsibilities
```

## Implementation

### Selection Plugin (`src/plugins/selection/core.cljc`)

```clojure
(ns plugins.selection.core)

(defn select [DB ids]
  "Replace selection with ids."
  (assoc DB :selection (set ids)))

(defn extend [DB id]
  "Add id to selection."
  (update DB :selection (fnil conj #{}) id))

(defn deselect [DB id]
  "Remove id from selection."
  (update DB :selection disj id))

(defn clear [DB]
  "Clear selection."
  (dissoc DB :selection))

(defn selected? [DB id]
  "Check if id is selected."
  (contains? (:selection DB #{}) id))
```

### Multi-select Structural Ops (`src/plugins/struct/core.cljc`)

```clojure
(defmethod compile-intent :delete-selected
  [DB _]
  (let [selected (get DB :selection #{})]
    (mapv #(delete-ops DB %) selected)))

(defmethod compile-intent :indent-selected
  [DB _]
  (let [selected (get DB :selection #{})]
    (mapcat #(indent-ops DB %) selected)))
```

### Undo/Redo (`src/core/history.cljc`)

```clojure
(defn record [history DB]
  "Record DB snapshot to undo stack."
  (-> history
      (update :past conj DB)
      (assoc :future [])))  ;; Clear redo stack on new action

(defn undo [history current-DB]
  "Restore previous DB state."
  (when-let [prev-DB (peek (:past history))]
    {:db prev-DB
     :history (-> history
                  (update :past pop)
                  (update :future conj current-DB))}))

(defn redo [history current-DB]
  "Restore next DB state."
  (when-let [next-DB (peek (:future history))]
    {:db next-DB
     :history (-> history
                  (update :future pop)
                  (update :past conj current-DB))}))
```

## Properties/Laws

1. **Single Value Flow**: Entire DB (core + derived + UI) is one immutable value
2. **Namespace Isolation**: Each concern occupies distinct namespace (`:nodes`, `:derived`, `:selection`, `:ui`)
3. **Transactional Consistency**: Undo/redo restores complete state atomically
4. **Plugin Composability**: Plugins can read any namespace, write to their namespace
5. **REPL Transparency**: All state visible in single data structure

## Tradeoffs

### Benefits
- **Simplicity**: Single DB value, no coordination between multiple atoms
- **Consistency**: Undo/redo automatically includes UI state
- **Debuggability**: One place to inspect all state
- **Pattern alignment**: Matches existing `:derived` namespace pattern
- **Event sourcing purity**: Entire DB flows through transaction log

### Costs
- **Namespace discipline**: Plugins must respect namespace boundaries (enforced by code review)
- **DB growth**: UI state increases DB size (acceptable for REPL-driven development)
- **History size**: Undo stack includes UI state (mitigated by snapshot limits)

## Alternatives Considered

**Alternative 1: Separate app-state atom**
```clojure
(atom {:doc kernel-DB :selection #{} :ui {}})
```
- ❌ Fights event sourcing (bifurcated state)
- ❌ Undo/redo requires coordinating multiple atoms
- ❌ Less REPL-friendly (multiple places to look)

**Alternative 2: Component local state**
```clojure
;; Selection in React component state
(let [selected (r/atom #{})] ...)
```
- ❌ Not inspectable from REPL
- ❌ Lost on component unmount
- ❌ Can't undo/redo

**Alternative 3: Dynamic vars (Logseq pattern)**
```clojure
(def ^:dynamic *selection* #{})
```
- ❌ Violates project constraint: "No dynamic Vars"
- ❌ Hidden state, harder to debug
- ❌ Not REPL-friendly

## Related Decisions
- ADR-001: Structural Edits as Lowering (plugins compile to core ops)
- ADR-010: Order Maintenance (derived indexes at DB root)

## Notes
This decision emerged from comparing Logseq's architecture (dynamic vars + atom-based state) against evo's event-sourced kernel. The key insight: if `:derived` lives at DB root as computed state, ephemeral UI state should also live there as plugin-managed state. Both flow through the same transaction pipeline, maintaining single-value purity.
