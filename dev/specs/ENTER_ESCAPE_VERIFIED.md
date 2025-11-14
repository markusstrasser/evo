# VERIFIED: Enter and Escape Behavior Analysis

**Date**: 2025-11-13
**Status**: ✅ VERIFIED from Logseq source code

---

## Summary

| Behavior | My Implementation | Logseq Actual | Match? |
|----------|-------------------|---------------|--------|
| **Enter on selected block** | Enter edit, cursor at END | Enter edit, cursor at END | ✅ CORRECT |
| **Escape while editing** | Exit + SELECT block | Exit + CLEAR (no select) | ❌ **WRONG** |

---

## Enter Key - Selected Block Behavior

### Logseq Implementation

**Shortcut binding**: `config.cljs:296-298`
```clojure
:editor/open-edit {:binding "enter"
                   :fn (fn [e]
                         (editor-handler/open-selected-block! :right e))}
```

**Handler**: `editor.cljs:3426-3439`
```clojure
(defn open-selected-block!
  [direction e]
  (when-not (auto-complete?)
    (let [selected-blocks (state/get-selection-blocks)
          f (case direction :left first :right last)
          node (some-> selected-blocks f)]
      (when-let [block-id (some-> node (dom/attr "blockid") uuid)]
        (util/stop e)
        (let [block {:block/uuid block-id}
              left? (= direction :left)
              opts {:container-id (some-> node (dom/attr "containerid") (parse-long))
                    :event e}]
          (edit-block! block (if left? 0 :max) opts))))))
```

**Key line**: `(edit-block! block (if left? 0 :max) opts)`
- Called with `:right`, so `left?` = false
- Position = `:max` (end of block)

**Behavior**:
1. Get selected blocks
2. Take the last block (`:right`)
3. Enter edit mode in that block
4. Cursor position = `:max` (end of text)

### My Implementation

```clojure
(intent/register-intent! :enter-edit-selected
  {:handler (fn [db _intent]
              (let [focused-block (get-in db [:nodes const/session-selection-id :props :focus])]
                (when focused-block
                  (let [text-length (count (get-in db [:nodes focused-block :props :text] ""))]
                    [{:op :update-node
                      :id const/session-selection-id
                      :props {:nodes #{} :focus nil :anchor nil}}
                     {:op :update-node
                      :id const/session-ui-id
                      :props {:editing-block-id focused-block
                              :cursor-position text-length}}]))))})
```

**Behavior**:
1. Get focused block
2. Calculate text length
3. Enter edit mode
4. Cursor position = `text-length` (end of text)

**✅ MATCH**: Both place cursor at END of block text

---

## Escape Key - Editing Behavior

### Logseq Implementation

**Shortcut binding**: `config.cljs:196-198`
```clojure
:editor/escape-editing {:binding []
                        :fn (fn [_ _]
                              (editor-handler/escape-editing))}
```

**Handler**: `editor.cljs:3897-3910`
```clojure
(defn escape-editing
  [& {:keys [select? save-block?]
      :or {save-block? true}}]  ;; ← NOTE: NO DEFAULT for select?
  (let [edit-block (state/get-edit-block)]
    (p/do!
     (when save-block? (save-current-block!))
     (if select?  ;; ← When called with no args, select? is nil (falsy)
       (when-let [node (some-> (state/get-input) (util/rec-get-node "ls-block"))]
         (state/exit-editing-and-set-selected-blocks! [node]))
       ;; ↓ THIS BRANCH RUNS (select? is nil)
       (when (= (:db/id edit-block) (:db/id (state/get-edit-block)))
         (state/clear-edit!))))))
```

**Critical detail**: The `:or` map only provides default for `save-block?`, NOT for `select?`.

When called as `(escape-editing)` with no arguments:
- `select?` = nil (not provided, no default)
- Goes to `else` branch
- Calls `(state/clear-edit!)` - just clears editing state

**Behavior**:
1. Save block
2. Clear edit state
3. **Does NOT select the block**

### My Implementation (WRONG)

```clojure
(intent/register-intent! :exit-edit-and-select
  {:handler (fn [db _intent]
              (when-let [editing-block-id (get-in db [:nodes const/session-ui-id :props :editing-block-id])]
                [{:op :update-node
                  :id const/session-ui-id
                  :props {:editing-block-id nil :cursor-position nil}}
                 {:op :update-node
                  :id const/session-selection-id
                  :props {:nodes #{editing-block-id}  ;; ← WRONG: Selects block
                          :focus editing-block-id
                          :anchor editing-block-id}}]))})
```

**Behavior**:
1. Clear edit state
2. **Selects the block** ← **THIS IS WRONG**

**❌ MISMATCH**: Logseq does NOT select, my implementation DOES select

---

## Required Fix

### Change Intent Name and Implementation

**From**:
```clojure
(intent/register-intent! :exit-edit-and-select ...)
```

**To**:
```clojure
(intent/register-intent! :exit-edit
  {:doc "Exit edit mode WITHOUT selecting block (Logseq parity)."
   :handler (fn [db _intent]
              [{:op :update-node
                :id const/session-ui-id
                :props {:editing-block-id nil :cursor-position nil}}])})
```

### Update Keymap

Keymap is ALREADY correct:
```clojure
[{:key "Escape"} :exit-edit]  ;; ← Already points to right intent
```

Just need to make sure `:exit-edit` intent does NOT select the block.

---

## When DOES Logseq Select After Escape?

Looking at call sites, `select?: true` is explicitly passed in specific contexts:

**config.cljs:36** - Some specific command:
```clojure
(editor-handler/escape-editing {:select? true})
```

**config.cljs:492** - Another specific context:
```clojure
(editor-handler/escape-editing {:select? true})
```

But the default Escape key binding (line 196) calls it **without parameters**, which means `select?` = nil = no selection.

---

## Final Verified Behavior

### ✅ Enter on Selected Block
- **Logseq**: Enters edit mode, cursor at END
- **Evo**: Enters edit mode, cursor at END
- **Status**: CORRECT

### ❌ Escape While Editing
- **Logseq**: Exits edit mode, does NOT select block
- **Evo**: Exits edit mode, SELECTS block
- **Status**: WRONG - needs fix

---

## Source References

1. **Logseq shortcuts**: `logseq/src/main/frontend/modules/shortcut/config.cljs`
   - Line 196-198: `:editor/escape-editing` shortcut
   - Line 296-298: `:editor/open-edit` shortcut

2. **Logseq handlers**: `logseq/src/main/frontend/handler/editor.cljs`
   - Line 3897-3910: `escape-editing` function
   - Line 3426-3439: `open-selected-block!` function

3. **Evo implementation**: `src/plugins/editing.cljc`
   - Line 36-40: `:exit-edit` intent (correct)
   - Line 42-55: `:exit-edit-and-select` intent (WRONG - should not select)
   - Line 57-71: `:enter-edit-selected` intent (correct)
