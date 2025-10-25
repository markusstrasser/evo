# Proposal: Command Composition for Intent Router

**Status:** Discussion
**Created:** 2025-10-24
**Related:** ADR-016 (Intent Router)

## Problem

Complex keymaps need fallback behavior where a key tries multiple intents until one succeeds:

- **Tab**: Try accept-suggestion → indent → insert-tab
- **Enter**: Try accept-autocomplete → split-block → create-sibling
- **Backspace**: Try delete-selection → delete-char → merge-with-prev → outdent

Currently this requires nested conditionals in UI layer, mixing presentation with behavior logic.

## Proposed Solution

Add `chain-intents` helper that tries intents in sequence until one succeeds:

```clojure
(defn chain-intents
  "Try intents in order until one succeeds (produces ops or changes db).
   Returns result from first success, or :unknown if all fail."
  [db & intents]
  (reduce
   (fn [_result intent]
     (let [result (apply-intent db intent)]
       (if (or (seq (:ops result))              ;; Produced ops?
               (not= (:db result) db))          ;; Changed db?
         (reduced result)                       ;; Success! Stop
         result)))                              ;; Try next
   {:db db :ops [] :path :unknown}
   intents))
```

## Use Cases

### 1. Context-Aware Tab Key
```clojure
;; Before: Complex conditional
(defn handle-tab! []
  (cond
    (has-suggestion?) (accept-suggestion!)
    (can-indent?) (indent!)
    :else (insert-tab!)))

;; After: Declarative fallback chain
(defn handle-tab! []
  (swap! !db
    (fn [db]
      (let [result (chain-intents db
                     {:type :accept-suggestion}
                     {:type :indent :id (focused-id)}
                     {:type :insert-text :text "\t"})]
        (apply-result result)))))
```

### 2. Smart Enter Key
```clojure
(chain-intents db
  {:type :accept-autocomplete}       ;; If menu open
  {:type :split-block}               ;; If cursor mid-text
  {:type :create-sibling-after}      ;; If cursor at end
  {:type :no-op})
```

### 3. Context-Aware Backspace
```clojure
(chain-intents db
  {:type :delete-selection}          ;; If text selected
  {:type :delete-char-before}        ;; If cursor mid-text
  {:type :merge-with-prev}           ;; If cursor at start
  {:type :outdent})                  ;; If empty block
```

## Comparison: ProseMirror Pattern

ProseMirror has `chainCommands`:
```javascript
const tabKey = chainCommands(
  acceptSuggestion,
  indentBlock,
  insertTab
)
// Commands return boolean (success/fail)
// First true stops chain
```

Our version is similar but uses our intent system (ops/db changes indicate success).

## Implementation

### Basic Version
```clojure
(defn chain-intents [db & intents]
  (reduce
   (fn [_result intent]
     (let [result (apply-intent db intent)]
       (if (or (seq (:ops result))
               (not= (:db result) db))
         (reduced result)
         result)))
   {:db db :ops [] :path :unknown}
   intents))
```

Total: ~15 lines

### Advanced: Custom Success Predicate
```clojure
(defn chain-intents-while
  "Try intents until predicate returns true on result."
  [db pred & intents]
  (reduce
   (fn [_result intent]
     (let [result (apply-intent db intent)]
       (if (pred result)
         (reduced result)
         result)))
   {:db db :ops [] :path :unknown}
   intents))

;; Usage: "Succeeded if not :unknown"
(chain-intents-while db #(not= :unknown (:path %))
  {:type :intent-a}
  {:type :intent-b})
```

Total: ~25 lines for both versions

## When to Implement

- ✅ Complex keymaps (Tab tries 3+ things)
- ✅ Context-aware key behavior
- ✅ Reducing UI conditional logic
- ❌ Simple keymaps (one intent per key)
- ❌ No autocomplete/suggestions yet

## Usage Pattern

```clojure
;; In keyboard handler
(defn on-key-down [e]
  (case (.-key e)
    "Tab"
    (swap! !db
      (fn [db]
        (let [result (chain-intents db
                       {:type :accept-suggestion}
                       {:type :indent :id (focused-id)}
                       {:type :insert-text :text "\t"})]
          (apply-result db result))))

    "Enter"
    (swap! !db
      (fn [db]
        (let [result (chain-intents db
                       {:type :accept-autocomplete}
                       {:type :split-block}
                       {:type :create-sibling-after})]
          (apply-result db result))))

    ;; ...
    ))
```

## Tradeoffs

**Pros:**
- Declarative fallback chains
- Less nested conditionals in UI
- Easy to reorder/add fallbacks
- Clear "try this, else that" logic

**Cons:**
- Slight performance cost (tries intents until success)
- Need clear definition of "success"
- May hide complexity (many fallbacks)
- Debugging: which intent actually fired?

## Alternative: Keep Conditionals in UI

Could argue that fallback logic belongs in UI layer:
```clojure
;; Explicit conditionals are clearer?
(cond
  (has-suggestion?) (apply-intent! {:type :accept-suggestion})
  (can-indent?) (apply-intent! {:type :indent ...})
  :else (apply-intent! {:type :insert-text ...}))
```

**Counter-argument**: Composition is more reusable and testable. Can test fallback chains without UI.

## Decision

**Defer until keymaps get complex.** Add when we implement:
- Autocomplete/suggestions (Tab needs fallback)
- Context-aware Enter (split vs create-sibling)
- Smart Backspace (multiple behaviors)

Implementation is trivial (~15 lines), safe to add incrementally.

## Future: Compose with Plugin Hooks

If we add plugin hooks (proposal 02), composition could chain transformed intents:

```clojure
;; Hook transforms intent
(defmethod before-apply-intent :indent [db intent]
  {:intents [{:type :expand :id (prev-sibling db (:id intent))}
             intent]})

;; Composition tries multiple top-level intents
(chain-intents db
  {:type :accept-suggestion}
  {:type :indent ...}    ;; Hook expands this to 2 intents
  {:type :insert-tab})
```

Hooks and composition are complementary extensions.
