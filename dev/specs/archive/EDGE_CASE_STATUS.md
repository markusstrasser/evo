# Edge Case Implementation Status

**Date**: 2025-11-13
**Branch**: feature/edge-case-implementations

---

## Summary

After discovering edge cases through Logseq source analysis, verification shows that **most critical edge cases are already implemented** in Evo! The bugs found were:

1. ✅ **Fixed**: Outdent positioning (wrong :at position)
2. ✅ **Fixed**: No-focus navigation (Down/Up after Escape)

---

## Edge Cases - Implementation Status

### ✅ Already Implemented Correctly

| Edge Case | Implementation | File:Line |
|-----------|---------------|-----------|
| **Indent first block → no-op** | Returns `[]` when no prev sibling | `struct.cljc:32-34` |
| **Navigation at boundary → cursor move** | Moves cursor to start/end | `navigation.cljc:211-216` |
| **Folded block skipping** | Uses `visible-in-context?` filter | `navigation.cljc:42-62` |
| **Outdent with children** | Children stay under parent (logical outdenting) | `struct.cljc:86-88` |
| **Outdent at root** | Prevented by grandparent check | `struct.cljc:85` |

### ✅ Recently Fixed

| Edge Case | Fix | Commit |
|-----------|-----|--------|
| **Outdent positioning** | Changed `:at :last` → `:at {:after p}` | d9f2e81 |
| **No-focus navigation** | Added `get-first-last-visible-block` | d9f2e81 |

### 🔍 Not Yet Implemented (Lower Priority)

| Edge Case | Logseq Behavior | Impact |
|-----------|----------------|---------|
| **Enter/Backspace/Tab with no selection** | No-op (only work when editing/selecting) | Low - rare user action |
| **Paste multi-line text** | Splits into blocks via parser | Medium - useful feature |
| **Zoom boundary navigation** | Stops at zoom boundaries | Low - zoom is advanced feature |

---

## Code Verification Details

### 1. Indent First Block No-Op

**Location**: `src/plugins/struct.cljc:28-34`

```clojure
(defn indent-ops
  "Compiles an indent intent into a :place operation that moves the node
   under its previous sibling."
  [db id]
  (if-let [sib (q/prev-sibling db id)]  ; ← Checks for previous sibling
    [{:op :place :id id :under sib :at :last}]
    []))  ; ← Returns empty vector = no-op when no previous sibling
```

**Matches Logseq**: `logseq/deps/outliner/src/logseq/outliner/core.cljs:1094`
```clojure
(when left  ; ← Same check
  ...)
```

---

### 2. Navigation Boundary Fallback

**Location**: `src/plugins/navigation.cljc:209-216`

```clojure
(if-not target-id
  ;; No target block - stay in current block with cursor at boundary
  [{:op :update-node
    :id const/session-ui-id
    :props {:editing-block-id current-block-id
            :cursor-position (if (= direction :up)
                               0  ; ← Up = start
                               (count current-text))}}]  ; ← Down = end
```

**Matches Logseq**: `logseq/src/main/frontend/handler/editor.cljs:2675-2677`
```clojure
(case direction
  :up (cursor/move-cursor-to input 0)
  :down (cursor/move-cursor-to-end input))
```

---

### 3. Folded Block Skipping

**Location**: `src/plugins/navigation.cljc:17-62`

```clojure
(defn- visible-in-context?
  "Check if a block is visible given fold and zoom context..."
  [db block-id]
  (let [zoom-root (q/zoom-root db)
        in-zoom? (or (nil? zoom-root) ...)
        no-folded-ancestors? (loop [current ...]
                               (cond
                                 (q/folded? db current) false  ; ← Skip if folded
                                 ...))]
    (and in-zoom? no-folded-ancestors?)))

(defn- get-prev-visible-block
  [db block-id]
  (loop [current-id (get-in db [:derived :prev-id-of block-id])]
    (cond
      (nil? current-id) nil
      (visible-in-context? db current-id) current-id  ; ← Only return if visible
      :else (recur ...))))  ; ← Keep searching
```

**Matches Logseq**: Uses `get-blocks-noncollapse` DOM query that excludes collapsed blocks. Evo's approach is **better** - it uses DB state instead of DOM queries, making it more testable and framework-agnostic.

---

## Architectural Observations

### Why These Were Already Correct

1. **Good defensive programming**: Evo uses `when-let` and `if-let` consistently, naturally handling nil cases
2. **Pure functions**: Edge cases are easier to reason about when functions are pure
3. **Explicit nil returns**: Functions return `[]` or `nil` for no-ops, making behavior clear

### Pattern Matching with Logseq

| Logseq Pattern | Evo Equivalent | Status |
|----------------|----------------|---------|
| `(when left ...)` | `(if-let [sib ...] ...)` | ✅ Same semantics |
| State guards first | Intent handlers return `[]` | ✅ Same pattern |
| DOM queries for collapsed | DB state for folded | ✅ Better approach |
| Nil = no-op | Empty vec = no-op | ✅ Equivalent |

---

## Recommendations

### 1. Document Edge Cases in Spec

Add explicit edge case documentation to `LOGSEQ_SPEC.md`:

```markdown
## Edge Case Handling

### Indent
- First block (no previous sibling): **No-op** - returns empty operation list

### Navigation (Editing)
- At first block, Up Arrow: Cursor moves to position 0
- At last block, Down Arrow: Cursor moves to end of text
- No visible blocks: Navigation returns nil (no-op)

### Navigation (Viewing)
- No focus (after Escape): Down → first block, Up → last block
- At boundaries: No-op (stays on same block)
```

### 2. Add Regression Tests

Create property-based tests for edge cases:

```clojure
(deftest indent-first-block-test
  (let [db (fix/db-with-single-block "a")]
    (is (= [] (struct/indent-ops db "a")))))

(deftest navigation-at-boundary-test
  (let [db (fix/db-with-single-block "a" "Hello")]
    ;; Down at last block → cursor to end
    (is (= :end (get-cursor-pos-after-nav db "a" :down)))))
```

### 3. Low Priority Implementations

Only implement these if users request them:

- **Paste multi-line**: Requires markdown parser integration
- **No-selection actions**: Users rarely press Enter/Tab with nothing selected
- **Zoom boundaries**: Advanced feature, low usage

---

## Conclusion

**Evo's edge case handling is solid!** The original architecture with:
- Pure functions
- Defensive nil handling
- Explicit empty returns
- DB-based state checks

...naturally handles most edge cases correctly. The two bugs found (outdent positioning, no-focus navigation) were:

1. **Spec errors** (not implementation bugs)
2. **Missing features** (not broken features)

This is a testament to good architectural decisions made early in the project.
