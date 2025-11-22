# Plugin Session Access Design

**Date**: 2025-11-21
**Status**: Design Proposal
**Context**: After Phases 4-5, plugins need access to session state but handlers only receive `db`

## Problem Statement

After removing session nodes from the DB (Phases 4-5), we have a fundamental architectural mismatch:

1. **Session state now lives in separate atom** (`shell.session/!session`)
2. **Plugins still need session data** for their operations:
   - Selection plugin: needs current selection to compute extend/toggle
   - Folding plugin: needs fold state to compute visible children
   - Navigation plugin: needs fold/zoom state for visibility checks
3. **Intent handlers only receive `db`**: `(handler db intent) -> ops`

## Current Temporary Solution

Plugins return stub data to allow compilation:

```clojure
;; plugins/selection.cljc
(defn- get-selection-state [db]
  {:nodes #{} :focus nil :anchor nil})  ;; Always empty!

;; plugins/visible_order.cljc
(defn- get-folded-set [db] #{})  ;; No blocks folded
(defn- get-zoom-root [db] nil)   ;; Never zoomed
```

**Impact**: Plugins produce incorrect results. Tests fail. User features broken.

## Design Options

### Option 1: Update Intent Handler Signature ⭐ RECOMMENDED

**Change**: Add session parameter to all handlers

```clojure
;; Before
(defhandler :select
  :spec ...
  :handler (fn [db intent] ...))

;; After
(defhandler :select
  :spec ...
  :handler (fn [db session intent] ...))
```

**Implementation**:

1. Update `kernel.intent` to pass session to handlers
2. Update `kernel.api/dispatch*` to get session from `shell.session`
3. Update all plugin handlers to accept `session` parameter
4. Update tests to pass session explicitly

**Pros**:
- ✅ Clean separation: DB = persistent, session = ephemeral
- ✅ Explicit dependencies: handlers declare they need session
- ✅ Testable: tests control both DB and session state
- ✅ No coupling: kernel doesn't depend on shell

**Cons**:
- ⚠️ Breaking change: all handlers need update
- ⚠️ More parameters: `(db, session, intent)` is verbose

**Files to Change**:
- `src/kernel/intent.cljc` - Update handler invocation
- `src/kernel/api.cljc` - Get session from shell
- `src/plugins/*.cljc` - Update all handlers (8 files)
- `test/**/*.cljc` - Update test fixtures (~50 files)

### Option 2: Session Accessor in DB

**Change**: Store session atom reference in DB metadata

```clojure
(def !db
  (atom (with-meta (db/empty-db)
                   {::session-fn #(deref shell.session/!session)})))

;; In handlers
(defn handler [db intent]
  (let [session ((::session-fn (meta db)))]
    ...))
```

**Pros**:
- ✅ No signature change: handlers still `(db, intent)`
- ✅ Lazy access: only get session when needed

**Cons**:
- ❌ Tight coupling: DB metadata depends on shell
- ❌ Not testable: hard to mock session
- ❌ Hidden dependency: handlers implicitly need session
- ❌ Meta abuse: metadata for runtime state is anti-pattern

### Option 3: Move Session-Dependent Logic to UI Layer

**Change**: Remove plugins that need session, handle in components

```clojure
;; Instead of intent:
;; {:type :select :mode :extend :ids ["a"]}

;; Component directly updates session:
(session/swap-session!
  update-in [:selection :nodes] conj "a")
```

**Pros**:
- ✅ Simple: no plugin complexity
- ✅ Fast: direct session updates (no intent compilation)
- ✅ Clear: session updates are session updates

**Cons**:
- ❌ No undo/redo: session changes not in history
- ❌ Duplicated logic: selection/navigation logic in components
- ❌ No validation: direct updates bypass intent spec validation
- ❌ Breaks architecture: bypasses intent layer

### Option 4: Hybrid - Session Ops

**Change**: Create special "session ops" that transaction layer handles differently

```clojure
;; Plugins generate session ops
{:op :session/update
 :path [:selection :nodes]
 :value #{\"a\" \"b\"}}

;; Transaction layer intercepts
(defn interpret [db ops]
  (doseq [op ops]
    (case (:op op)
      :session/update (session/swap-session! assoc-in (:path op) (:value op))
      (apply-to-db db op))))
```

**Pros**:
- ✅ Ops-based: maintains event sourcing
- ✅ History-compatible: can record session ops
- ✅ No handler changes: handlers still return ops

**Cons**:
- ❌ Circular dependency: kernel → shell (session updates)
- ❌ Complex: two different op types
- ❌ Unclear ownership: who updates session?

## Recommendation: Option 1

**Update intent handler signature to include session.**

### Rationale

1. **Clean Architecture**: Explicit dependencies, no coupling
2. **Testability**: Tests have full control
3. **Maintainability**: Clear what each handler needs
4. **Consistency**: Matches query layer (already session-aware)

### Migration Path

**Phase 1**: Update Infrastructure (Kernel)
```clojure
;; kernel/intent.cljc
(defn apply-intent
  "Compile intent to ops using registered handler."
  [db session intent]  ;; Add session param
  (let [handler (get-handler (:type intent))]
    (handler db session intent)))  ;; Pass session

;; kernel/api.cljc
(defn dispatch*
  [db intent]
  (let [session (shell.session/get-session)  ;; Get from shell
        {:keys [ops]} (intent/apply-intent db session intent)]
    ...))
```

**Phase 2**: Update Plugins (One at a Time)
```clojure
;; plugins/selection.cljc
(defhandler :select
  :handler (fn [db session intent]  ;; Add session param
             (let [state (q/selection-state session)  ;; Use session
                   ...]
               ...)))
```

**Phase 3**: Update Tests
```clojure
;; test fixtures
(def test-session
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil}
   :ui {:folded #{} :zoom-root nil}})

;; Test helper
(defn test-dispatch [db session intent]
  (api/dispatch* db intent))
```

**Phase 4**: Remove Stubs
- Delete temporary stub functions
- Restore proper session queries
- Re-enable range selection

### Estimated Effort

- **Infrastructure**: 2-3 hours (intent.cljc, api.cljc)
- **Plugins**: 4-6 hours (8 plugins)
- **Tests**: 8-12 hours (50+ test files)
- **Total**: 14-21 hours

### Rollout Strategy

1. ✅ Update infrastructure (kernel.intent, kernel.api)
2. ✅ Create test helper fixtures
3. ✅ Update one plugin as proof-of-concept (selection)
4. ✅ Update remaining plugins
5. ✅ Fix all test failures
6. ✅ Remove temporary stubs
7. ✅ Run full test suite + E2E
8. ✅ Merge to main

## Alternative: Quick Fix for Demo

If we need working functionality quickly:

**Store session reference in DB atom metadata** (Option 2) as temporary solution:

```clojure
;; shell/blocks_ui.cljs
(swap! !db with-meta {::session-atom shell.session/!session})

;; plugins/selection.cljc
(defn- get-selection-state [db]
  (if-let [session-atom (::session-atom (meta db))]
    (q/selection-state @session-atom)
    {:nodes #{} :focus nil :anchor nil}))  ;; Fallback for tests
```

**Pros**: Minimal code change, works immediately
**Cons**: Technical debt, harder to test, breaks in tests

Use only if Option 1 timeline is too long.

## Decision Record

**Decision**: Proceed with **Option 1** (Update handler signature)

**Date**: 2025-11-21

**Reasons**:
1. Clean architecture (no coupling)
2. Testable design
3. Explicit dependencies
4. Matches query layer design
5. Long-term maintainability

**Next Steps**:
1. Update `kernel.intent/apply-intent` to pass session
2. Update `kernel.api/dispatch*` to get session
3. Update plugins one-by-one
4. Fix test failures
5. Run E2E verification

**Risks**:
- Large refactor across many files
- Tests will fail until all updated
- Requires careful coordination

**Mitigation**:
- Feature branch (already on `refactor/session-atom`)
- Incremental updates (one plugin at a time)
- Test-driven approach (fix tests as we go)
- E2E verification at end
