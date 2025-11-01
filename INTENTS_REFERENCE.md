# Complete Intent Reference

This document lists all registered intents in the system with their specifications.

## Selection Intents

### `:selection` (Unified Selection Reducer)
**Modes:**
- `:replace` - Replace selection with given IDs
- `:extend` - Add IDs to selection (supports range)
- `:deselect` - Remove IDs from selection
- `:toggle` - Toggle ID in/out of selection
- `:clear` - Clear all selection
- `:next` - Select next sibling
- `:prev` - Select previous sibling
- `:extend-next` - Extend to next sibling
- `:extend-prev` - Extend to previous sibling
- `:parent` - Select parent (if unique)
- `:all-siblings` - Select all siblings

**Examples:**
```clojure
{:type :selection :mode :replace :ids ["a" "b"]}
{:type :selection :mode :extend :ids "c"}
{:type :selection :mode :next}
{:type :selection :mode :clear}
```

---

## Editing Intents

### `:enter-edit`
Enter edit mode for a block.
```clojure
{:type :enter-edit :block-id "a" :cursor-at :start}
```
**Args:**
- `:block-id` (required) - Block to edit
- `:cursor-at` (optional) - `:start` or `:end`

**State:** Ephemeral (UI node)

---

### `:exit-edit`
Exit edit mode.
```clojure
{:type :exit-edit}
```

**State:** Ephemeral (UI node)

---

### `:update-content`
Update block text content.
```clojure
{:type :update-content :block-id "a" :text "new text"}
```

**State:** Persistent (undo history)

---

### `:update-cursor-state`
Update cursor position boundary detection.
```clojure
{:type :update-cursor-state :block-id "a" :first-row? true :last-row? false}
```

**State:** Ephemeral (UI node)

---

### `:merge-with-prev`
Merge block with previous sibling.
```clojure
{:type :merge-with-prev :block-id "a"}
```

**State:** Persistent (undo history)

---

### `:split-at-cursor`
Split block at cursor position.
```clojure
{:type :split-at-cursor :block-id "a" :cursor-pos 5}
```

**State:** Persistent (undo history)

---

## Smart Editing Intents

### `:merge-with-next`
Merge block with next sibling.
```clojure
{:type :merge-with-next :block-id "a"}
```

**State:** Persistent (undo history)

---

### `:unformat-empty-list`
Remove list marker from empty list item.
```clojure
{:type :unformat-empty-list :block-id "a"}
```

---

### `:split-with-list-increment`
Split block with auto-numbered list increment.
```clojure
{:type :split-with-list-increment :block-id "a" :cursor-pos 5}
```

---

### `:toggle-checkbox`
Toggle checkbox state.
```clojure
{:type :toggle-checkbox :block-id "a"}
```

---

## Folding Intents

### `:toggle-fold`
Toggle expand/collapse of a block.
```clojure
{:type :toggle-fold :block-id "a"}
```

---

### `:expand-all`
Recursively expand a block and descendants.
```clojure
{:type :expand-all :block-id "a"}
```

---

### `:collapse`
Collapse a block (hide children).
```clojure
{:type :collapse :block-id "a"}
```

---

### `:toggle-all-folds`
Toggle all folds on a page.
```clojure
{:type :toggle-all-folds :root-id "page"}
```

---

## Zoom Intents

### `:zoom-in`
Zoom into a block (make it rendering root).
```clojure
{:type :zoom-in :block-id "a"}
```

---

### `:zoom-out`
Zoom out to parent context.
```clojure
{:type :zoom-out}
```

---

### `:zoom-to`
Jump to breadcrumb position in zoom stack.
```clojure
{:type :zoom-to :block-id "a"}
```

---

### `:reset-zoom`
Reset zoom to document root.
```clojure
{:type :reset-zoom}
```

---

## Structural Intents (Single)

### `:delete`
Delete node by moving to trash.
```clojure
{:type :delete :id "a"}
```

---

### `:indent`
Indent node under previous sibling.
```clojure
{:type :indent :id "a"}
```

---

### `:outdent`
Outdent node to be sibling of parent.
```clojure
{:type :outdent :id "a"}
```

---

### `:create-and-place`
Create new block and place it.
```clojure
{:type :create-and-place :id "new-123" :parent "page" :after "a"}
```

---

### `:create-and-enter-edit`
Create new block after focus and enter edit mode.
```clojure
{:type :create-and-enter-edit}
```

---

## Structural Intents (Multi-Selection)

### `:delete-selected`
Delete all selected nodes (or editing block).
```clojure
{:type :delete-selected}
```

---

### `:indent-selected`
Indent all selected nodes (or editing block).
```clojure
{:type :indent-selected}
```

---

### `:outdent-selected`
Outdent all selected nodes (or editing block).
```clojure
{:type :outdent-selected}
```

---

### `:move-selected-up`
Move selected nodes up one sibling position.
```clojure
{:type :move-selected-up}
```

---

### `:move-selected-down`
Move selected nodes down one sibling position.
```clojure
{:type :move-selected-down}
```

---

## Movement/Reordering

### `:move`
Move selection to target parent at anchor position.
```clojure
{:type :move :selection ["a" "b"] :parent "page" :anchor :first}
{:type :move :selection ["a"] :parent "page" :anchor {:after "x"}}
```

**Anchor types:**
- `:first` - First position
- `:last` - Last position
- `{:after "id"}` - After specific node

---

## Reference Intents (Defined but not yet keybound)

### `add-link`
Add link from source to target.
**Not yet exposed as intent - use `refs/add-link-op` directly**

### `add-highlight`
Add highlight annotation.
**Not yet exposed as intent - use `refs/add-highlight-op` directly**

---

## State Summary

### Ephemeral State (Not in History)
Stored in `db[:nodes :session/ui :props]`:
- `:editing-block-id` - Currently editing block
- `:cursor-position` - Cursor hint (`:start` or `:end`)
- `:cursor` - Cursor position boundaries per block
- `:folded` - Set of folded block IDs
- `:zoom-stack` - Navigation history
- `:zoom-root` - Current zoom root

### Persistent State (In History)
- Block creation/deletion
- Text content changes
- Tree structure changes
- Selection changes

---

## How to Use in Code

### From REPL
```clojure
;; Dispatch intent
(api/dispatch db {:type :select :ids "a"})
;=> {:db updated-db :issues []}

;; With details
(let [{:keys [db issues]} (api/dispatch db intent)]
  (if (empty? issues)
    db
    (throw (ex-info "Intent failed" {:issues issues}))))

;; List all intents
(api/list-intents)
```

### From UI
```clojure
;; Components call on-intent callback
(on-intent {:type :select :mode :replace :ids "a"})

;; Global keymap resolves hotkeys to intents
(keymap/resolve-intent-type event db)
;=> :select
```

### From Tests
```clojure
(testing "delete intent"
  (let [db0 (test-db-with-blocks)
        result (api/dispatch db0 {:type :delete-selected})
        db1 (:db result)]
    (is (empty? (:issues result)))
    (is (deleted? db1 "block-id"))))
```

---

## Intent Handler Implementation Template

```clojure
(intent/register-intent! :my-intent
  {:doc "Description of what the intent does"
   :spec [:map
          [:type [:= :my-intent]]
          [:arg1 :string]
          [:arg2 {:optional true} :string]]
   :handler (fn [db {:keys [arg1 arg2]}]
              ;; Returns vector of operations
              [{:op :update-node
                :id arg1
                :props {:text "updated"}}])})
```

---

## Testing Intents

### Manual Testing
```clojure
;; Test indent
(let [{:keys [db]} (api/dispatch db {:type :indent :id "a"})]
  (is (= "parent-of-a" (q/parent-of db "a"))))

;; Test multi-selection
(let [db' (api/dispatch! db {:type :selection :mode :replace :ids ["a" "b"]})]
  (is (= #{"a" "b"} (q/selection db'))))
```

### Property Testing
Use `test.check` for intent chains and edge cases (see `test/core/`)

---

## Performance Notes

- Intents compile to 3-operation kernel (create, place, update)
- Transaction pipeline runs: normalize → validate → apply → derive
- History recording is automatic (except ephemeral ops)
- Derived indexes (parent-of, next-id-of, etc.) recomputed on every tx
- Selection is stored as session node, participates in undo/redo

---

## Extending the System

### Add Custom Intent

1. Define in `src/plugins/myfeature.cljc`:
```clojure
(intent/register-intent! :my-feature
  {:doc "..."
   :spec [...]
   :handler (fn [db intent] [...ops...])})
```

2. Add hotkey to `src/keymap/bindings_data.cljc`:
```clojure
{:global [[{:key "X"} :my-feature]]}
```

3. Reload:
```clojure
(keymap.bindings/reload!)
```

4. Test:
```clojure
(api/dispatch db {:type :my-feature})
```

---

## See Also

- **HOTKEYS_AND_ACTIONS.md** - Complete guide with architecture
- **HOTKEYS_QUICK_REFERENCE.md** - Quick lookup by hotkey
- **src/kernel/intent.cljc** - Intent registry implementation
- **src/plugins/*.cljc** - Intent handler implementations
- **src/keymap/bindings_data.cljc** - Hotkey definitions

