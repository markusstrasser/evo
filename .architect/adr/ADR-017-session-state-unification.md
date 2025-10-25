ADR-017: Session State Unification Through 3-Op Kernel

Status: Accepted
Date: 2025-10-25
Owner: Kernel team
Scope: Intent system, session state, undo/redo, transaction pipeline

⸻

## Context

Prior architecture had dual intent paths:
- **Structural intents** (`intent->ops`) compiled to operations, went through validate/derive pipeline
- **View intents** (`intent->db`) directly mutated DB state for selection/edit/cursor

This created several issues:
1. **Inconsistent undo/redo**: Document changes were undoable, but selection/edit state changes were not
2. **Split responsibility**: Two separate paths for state mutation made reasoning harder
3. **Duplication**: Anchor normalization (`:at-start` → `:first`) happened in multiple places
4. **Hidden mutations**: View state changes invisible in history/debug trace

Selection, editing, and cursor state were stored at DB root (`:selection`, `:view`), outside the node tree.

⸻

## Decision

**Unify all state changes through the 3-op kernel pipeline.**

1. **Session state as first-class nodes**: Add `:session` root with child nodes:
   - `session/selection` - stores `{:nodes #{id...} :focus id :anchor id}`
   - `session/edit` - stores `{:block-id id}`
   - `session/cursor` - stores cursor measurement state

2. **Remove `intent->db` multimethod entirely**: All intents compile to ops only

3. **Session state changes emit ops**: Selection/edit/cursor changes use `:update-node` on session nodes

4. **Consolidate reorder logic**: Route all reorder/move intents through `plugins.permute` (single lowering engine)

5. **Central anchor normalization**: Normalize all anchor synonyms once in `normalize-ops`

6. **Shared tree helpers**: Create `core.tree` namespace with `parent-of`, `prev-sibling`, `doc-range`, etc.

⸻

## Before/After Comparison

### Architecture

**Before:**
```clojure
;; Dual intent paths
(defmethod intent->ops :indent [db intent] [...])      ;; → ops → validate → derive
(defmethod intent->db :select [db intent] (assoc db :selection ...))  ;; → direct mutation

;; Selection stored at root
{:selection {:nodes #{} :focus nil}
 :view {:editing nil :cursor {...}}
 :nodes {...}
 :children-by-parent {...}}
```

**After:**
```clojure
;; Single intent path
(defmethod intent->ops :indent [db intent] [...])      ;; → ops → validate → derive
(defmethod intent->ops :select [db intent]             ;; → ops → validate → derive
  [{:op :update-node :id "session/selection" :props {...}}])

;; Session state as nodes under :session root
{:nodes {"session" {...}
         "session/selection" {:type :selection :props {:nodes #{} :focus nil}}
         "session/edit" {:type :edit :props {:block-id nil}}
         "session/cursor" {:type :cursor :props {...}}
         ...}
 :children-by-parent {:session ["session/selection" "session/edit" "session/cursor"]
                      ...}
 :roots #{:doc :trash :session}}
```

### Intent Handling

**Before:**
```clojure
;; In plugins/selection.cljc
(defmethod intent->db :select [DB {:keys [ids]}]
  (assoc DB :selection {:nodes (set ids) :focus (last ids)}))

;; In app - two paths
(let [{:keys [db ops path]} (intent/apply-intent db intent)]
  (case path
    :ops (tx/interpret db ops)    ;; Structural
    :db  db                        ;; View - direct mutation
    :unknown db))
```

**After:**
```clojure
;; In plugins/selection.cljc
(defmethod intent->ops :select [DB {:keys [ids]}]
  [{:op :update-node
    :id "session/selection"
    :props {:nodes (set ids) :focus (last ids) :anchor (last ids)}}])

;; In app - single path
(let [{:keys [ops]} (intent/apply-intent db intent)]
  (tx/interpret db ops))  ;; Everything goes through pipeline
```

### Reorder Consolidation

**Before:**
```clojure
;; Duplication across plugins.struct
(defmethod intent->ops :reorder/children [db {:keys [parent order]}]
  (let [p (target-permutation db parent order)]
    (realize-permutation->places db parent p)))

(defmethod intent->ops :move-selected-up [db _]
  (let [siblings (children db parent)
        dst (splice-after siblings targets before-prev)
        p (from-to siblings dst)]
    (realize-permutation->places db parent p)))  ;; Same logic, different entry
```

**After:**
```clojure
;; Single engine in plugins.permute
(defmethod intent->ops :reorder [db intent]
  (:ops (lower db (assoc intent :intent :reorder))))

;; All others route through it
(defmethod intent->ops :reorder/children [db {:keys [parent order]}]
  (intent->ops db {:type :reorder :selection order :parent parent :anchor :first}))

(defmethod intent->ops :move-selected-up [db _]
  (intent->ops db {:type :reorder :selection targets :parent parent
                   :anchor (if prev {:after prev} :first)}))
```

### Anchor Normalization

**Before:**
```clojure
;; Scattered across plugins
(defn normalize-anchor [a]  ;; In plugins.permute
  (case a :at-start :first :at-end :last a))

;; And in other places...
```

**After:**
```clojure
;; Once in core.transaction
(defn- canon-at [a]
  (cond
    (= a :at-start) :first
    (= a :at-end) :last
    (int? a) {:at-index a}
    :else a))

(defn- normalize-ops [db ops]
  (->> ops
       (map #(if (= (:op %) :place) (update % :at canon-at) %))
       ...))
```

⸻

## Consequences

### Positive ✅

1. **Full undo/redo coverage**
   - Selection, edit mode, cursor state all undoable
   - Cmd+Z can restore selection after accidental click
   - Complete audit trail of all state changes

2. **Single responsibility**
   - Transaction pipeline handles ALL state mutations
   - No special cases for "view state"
   - Easier to reason about data flow

3. **Better debugging**
   - All state changes visible in history trace
   - Can replay exact sequence of user actions
   - No hidden mutations

4. **Reduced duplication**
   - One reorder engine (`plugins.permute`)
   - One anchor normalization (in `normalize-ops`)
   - Shared tree helpers (`core.tree`)

5. **Architectural consistency**
   - Session state follows same patterns as document state
   - No cognitive overhead of "which path does this take?"

### Negative ⚠️

1. **Performance regression**
   - Every selection/cursor change runs through normalize→validate→derive
   - Old direct mutation was instant (O(1))
   - New path is O(n) where n = tree size for derive
   - **Mitigation**: Could batch rapid changes, or optimize derive for session-only ops

2. **Conceptual awkwardness**
   - Selection/cursor aren't really "document nodes"
   - Treating them as nodes under `:session` root bends the model
   - `:session` appears in tree traversal (pre/post order)
   - Need to filter session nodes from doc-range and similar operations

3. **Empty DB bloat**
   - Every DB starts with 4 session nodes
   - Added to `:nodes`, `:children-by-parent`, `:derived` indexes
   - Minor, but unnecessary overhead

4. **Test complexity**
   - Special handling for "nodes that are roots but not quite"
   - `is-root?` logic to exclude session nodes from orphan checks
   - Encode/decode tests need relaxed equality checks

5. **Fragility risk**
   - Session nodes in traversal could leak into doc operations
   - Need defensive checks like `(remove is-session-node? ...)`
   - One missed filter = subtle bug

### Trade-off Analysis

**For 80/20 solo dev with AI assistants:**
- ✅ Undo/redo and debugging wins align with stated priorities
- ✅ Debuggability > performance matches philosophy
- ✅ Architectural purity reduces mental overhead

**For production app:**
- ⚠️ Performance overhead on every selection change concerning
- ⚠️ Conceptual model awkwardness could confuse team
- ⚠️ Might prefer lighter-weight session undo + separate document undo

⸻

## Implementation

### Files Changed

**Core (5 files):**
- `src/core/db.cljc` - Add `:session` root and session nodes
- `src/core/intent.cljc` - Remove `intent->db`, simplify to ops-only
- `src/core/transaction.cljc` - Add central anchor normalization
- `src/core/tree.cljc` - **NEW** - Shared tree traversal helpers
- `src/core/schema.cljc` - Minor updates for session nodes

**Plugins (5 files):**
- `src/plugins/selection.cljc` - Migrate to ops on session nodes
- `src/plugins/editing.cljc` - Migrate to ops on session nodes
- `src/plugins/navigation.cljc` - Migrate to ops on session nodes
- `src/plugins/permute.cljc` - Add intent handlers
- `src/plugins/struct.cljc` - Route reorder/move through permute

**Tests (3 files):**
- `test/core_schema_test.cljc` - Relax encode/decode equality
- `test/core_transaction_test.cljc` - Handle session nodes in orphan check
- `test/plugins/struct_test.cljc` - Update for ops-only path

### Commits

1. `feat(db): add :session root and session nodes`
2. `refactor(intent): remove intent->db; migrate to ops-only`
3. `refactor(reorder): consolidate all reorder/move through plugins.permute`
4. `feat(tx): central anchor normalization in normalizer`
5. `feat(tree): add shared helpers and refactor call sites`
6. `fix: update tests for session state architecture`

### Test Results

**Before refactor:** 166/167 passing (1 pre-existing permute property test failure)
**After refactor:** 166/167 passing (same 1 pre-existing failure)

All new session state functionality tested and passing.

⸻

## Open Questions

1. **Performance monitoring needed**: Measure actual overhead on large documents (1000+ nodes)
   - If >10ms for selection change, consider batching or derive optimization

2. **Session node traversal**: Should we have separate traversal just for `:doc` descendants?
   - Pro: Cleaner, no filtering needed
   - Con: More code, two traversal systems

3. **Do we need full session undo/redo?** Or just document undo/redo?
   - If users rarely undo selection changes, we added complexity for unused feature
   - Consider usage metrics after shipping

⸻

## Alternatives Considered

### A) Keep dual intent paths, add session undo separately

```clojure
;; Keep intent->db for view state
(defmethod intent->db :select [db intent] ...)

;; But add lightweight history just for session
(def session-history (atom []))
(defn record-session-change! [before after] ...)
```

**Rejected because:**
- Still have two mutation paths (cognitive overhead)
- Now have TWO undo systems (document + session)
- More complexity overall

### B) Session state outside DB entirely

```clojure
;; Keep session in separate atom
(def !session (atom {:selection #{} :editing nil}))

;; Only document goes through kernel
```

**Rejected because:**
- Can't serialize full app state
- Can't replay user actions (debugging loss)
- Session and document state can desync

### C) Session state as virtual nodes (not in :nodes map)

```clojure
{:nodes {...}          ;; Only document nodes
 :session-nodes {...}  ;; Separate session nodes
 :children-by-parent {:session [...] ...}}  ;; Session in tree
```

**Rejected because:**
- Still need special case code
- Doesn't reduce complexity meaningfully
- Traversal still needs filtering

⸻

## Evaluation Criteria

**Success metrics (3 months):**
- [ ] No performance complaints from users
- [ ] Zero bugs from session/doc traversal confusion
- [ ] Undo/redo actually used in practice (telemetry)
- [ ] Debugging made easier (subjective team feedback)

**Failure signals:**
- Performance complaints about selection lag
- Multiple bugs from session nodes leaking into doc operations
- Team confusion about session node model
- Undo/redo feature unused (wasted complexity)

⸻

## Verdict

**7/10** - Architecturally cleaner, real undo/redo benefit, but non-trivial performance and conceptual costs.

Worth it for debuggability-first philosophy and 80/20 approach. Monitor performance and be prepared to optimize or revert if issues emerge.

⸻

## References

- ADR-016: Intent Router Pattern (established dual intent->ops/intent->db)
- `src/core/tree.cljc` - Shared tree utilities
- Refactor branch: `refactor/session-state-unify` (merged to main 2025-10-25)
