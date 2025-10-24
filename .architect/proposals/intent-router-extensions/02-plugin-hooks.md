# Proposal: Plugin Hooks for Intent Router

**Status:** Discussion
**Created:** 2025-10-24
**Related:** ADR-016 (Intent Router)

## Problem

Plugins cannot intercept intent application to:

1. **Validate before execution** - Fail fast with helpful errors instead of silent no-ops
2. **Enforce access control** - Block operations on locked/read-only content
3. **Audit operations** - Log structural changes for compliance/debugging
4. **Transform intents** - Auto-expand parent when indenting, chain related operations

Example: `indent` on first child silently returns empty ops. Better to fail immediately with "Cannot indent first child - no previous sibling."

## Proposed Solution

Add two multimethod hooks that plugins can implement:

```clojure
;; Before hook: validate, reject, or transform
(defmulti before-apply-intent
  (fn [_db intent] (:type intent)))

;; After hook: observe, log, side effects
(defmulti after-apply-intent
  (fn [_db intent _result] (:type intent)))

;; apply-intent calls hooks
(defn apply-intent [db intent opts]
  (let [intent' (before-apply-intent db intent)]
    (cond
      (:skip intent') {:db db :ops [] :path :cancelled}
      (:intents intent') (apply-multiple (:intents intent'))
      :else
      (let [result (apply-intent-impl db intent' opts)]
        (after-apply-intent db intent' result)
        result))))
```

## Use Cases

### 1. Pre-validation with Helpful Errors
```clojure
(defmethod before-apply-intent :indent [db {:keys [id]}]
  (when-not (prev-sibling db id)
    (throw (ex-info "Cannot indent first child"
                    {:id id
                     :reason :no-prev-sibling
                     :suggestion "Try indenting as child of parent"}))))
```

### 2. Access Control
```clojure
(defmethod before-apply-intent :delete [db {:keys [id] :as intent}]
  (if (locked? db id)
    {:skip true :reason (str "Block locked by " (locker-name db id))}
    intent))
```

### 3. Audit Logging
```clojure
(defmethod after-apply-intent :default [db intent result]
  (when (structural? result)
    (audit-log/record! {:timestamp (js/Date.now)
                        :intent intent
                        :ops (:ops result)})))
```

### 4. Intent Transformation
```clojure
;; Auto-expand parent when indenting
(defmethod before-apply-intent :indent [db {:keys [id] :as intent}]
  (let [target (prev-sibling db id)]
    (if (collapsed? db target)
      {:intents [{:type :expand :id target}  ;; Run first
                 intent]}                     ;; Then indent
      intent)))
```

## Implementation

1. Define two multimethods with `:default` pass-through (20 lines)
2. Update `apply-intent` to call hooks (30 lines)
3. Handle hook return values:
   - `{:skip true :reason "..."}` - Cancel with reason
   - `{:intents [...]}` - Replace with multiple intents
   - `intent` - Continue normally
   - Throw exception - Hard fail

Total: ~50 lines

## Hook Contract

### `before-apply-intent` Return Values
- **intent** (unchanged or modified) → Continue with intent
- **{:skip true :reason "..."}** → Cancel, return `:cancelled` path
- **{:intents [...]}** → Replace with multiple intents
- **Throw exception** → Hard fail, return `:error` path

### `after-apply-intent` Return Value
- Ignored (for side effects only)

## When to Implement

- ✅ Complex validation rules (block permissions, business constraints)
- ✅ Audit logging for compliance
- ✅ Plugin ecosystem with third-party extensions
- ✅ Intent chaining (auto-expand, related operations)
- ❌ Simple app with validation in UI layer

## Comparison: ProseMirror Pattern

ProseMirror uses `filterTransaction`:
```javascript
filterTransaction: (tr, state) => {
  // Can reject or modify transaction
  return tr.steps.length > 0
}
```

Our hooks are more granular (per-intent type) and explicit (separate before/after).

## Tradeoffs

**Pros:**
- Fail-fast validation with helpful errors
- Extensible without modifying core
- Clear plugin extension point
- Enables complex intent chaining

**Cons:**
- Two more multimethods to learn
- Potential for hook conflicts (multiple plugins)
- Performance cost if many hooks registered
- Need clear guidelines on hook usage

## Decision

**Defer until needed.** Add when we need:
- Complex validation (access control, business rules)
- Audit logging
- Intent transformation (auto-expand, etc.)

The architecture supports adding hooks non-invasively.
