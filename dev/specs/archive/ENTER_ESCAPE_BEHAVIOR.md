# Enter and Escape Behavior - Logseq Parity Analysis

**Date**: 2025-11-13
**Status**: 🚨 CRITICAL BUGS FOUND

---

## Current Evo Behavior (WRONG)

### Enter Key - Block Selected (Not Editing)
**Current**: Creates NEW empty block after selected block, enters edit mode in new block
**Keymap**: `{:key "Enter"} :create-and-enter-edit` (line 12 of bindings_data.cljc)

### Escape Key - While Editing
**Current**: Exits edit mode, cursor disappears, nothing selected
**Keymap**: `{:key "Escape"} :exit-edit` (line 13 of bindings_data.cljc)
**Implementation**: `editing.cljc:36-40` - Just clears `:editing-block-id`

### Escape Key - Block Selected (Not Editing)
**Current**: Clears selection entirely
**Keymap**: `{:key "Escape"} {:type :selection :mode :clear}` (line 4 of bindings_data.cljc)

---

## Logseq Behavior (CORRECT)

### Enter Key - Block Selected (Not Editing)
**Logseq**: Enters edit mode **in the selected block** at the END of text
**Where cursor goes**: End of the block's text content
**Does NOT**: Create a new block

**Source**: Need to verify in Logseq source

### Escape Key - While Editing
**Logseq**: Exits edit mode AND selects the block you were just editing
**Source**: `editor.cljs:3897-3910` - `escape-editing` function with `select?` parameter

```clojure
(defn escape-editing
  [& {:keys [select? save-block?]
      :or {save-block? true}}]
  (let [edit-block (state/get-edit-block)]
    (p/do!
     (when save-block? (save-current-block!))
     (if select?
       (when-let [node (some-> (state/get-input) (util/rec-get-node "ls-block"))]
         (state/exit-editing-and-set-selected-blocks! [node]))  ;; ← SELECTS BLOCK
       (when (= (:db/id edit-block) (:db/id (state/get-edit-block)))
         (state/clear-edit!))))))
```

**Key insight**: `select?` parameter controls whether block is selected after escape

### Escape Key - Block Selected (Not Editing)
**Logseq**: Clears selection (same as Evo)
**This one is correct!**

---

## Required Fixes

### Fix 1: Enter on Selected Block → Enter Edit Mode (Don't Create New Block)

**Change keymap**:
```clojure
;; BEFORE (wrong):
[{:key "Enter"} :create-and-enter-edit]

;; AFTER (correct):
[{:key "Enter"} {:type :enter-edit-selected}]
```

**New intent handler** (`editing.cljc`):
```clojure
(intent/register-intent! :enter-edit-selected
  {:doc "Enter edit mode in selected block at end of text (Logseq parity)."
   :spec [:map [:type [:= :enter-edit-selected]]]
   :handler (fn [db _intent]
              (when-let [focused-block (tree/focus db)]
                (let [text-length (count (get-in db [:nodes focused-block :props :text] ""))]
                  [{:op :update-node
                    :id const/session-selection-id
                    :props {:nodes #{} :focus nil :anchor nil}}
                   {:op :update-node
                    :id const/session-ui-id
                    :props {:editing-block-id focused-block
                            :cursor-position text-length}}])))})
```

### Fix 2: Escape While Editing → Select the Block

**Change keymap**:
```clojure
;; BEFORE (wrong):
[{:key "Escape"} :exit-edit]

;; AFTER (correct):
[{:key "Escape"} {:type :exit-edit-and-select}]
```

**New intent handler** (`editing.cljc`):
```clojure
(intent/register-intent! :exit-edit-and-select
  {:doc "Exit edit mode and select the block (Logseq parity)."
   :spec [:map [:type [:= :exit-edit-and-select]]]
   :handler (fn [db _intent]
              (when-let [editing-block-id (get-in db [:nodes const/session-ui-id :props :editing-block-id])]
                [{:op :update-node
                  :id const/session-ui-id
                  :props {:editing-block-id nil :cursor-position nil}}
                 {:op :update-node
                  :id const/session-selection-id
                  :props {:nodes #{editing-block-id}
                          :focus editing-block-id
                          :anchor editing-block-id}}]))})
```

---

## User Experience Flow (After Fix)

### Scenario 1: Navigate and Edit
```
1. [View] Block A, Block B, Block C
2. Down arrow → Block B selected
3. Enter → Edit mode in Block B, cursor at end
4. Type "hello"
5. Escape → Block B selected (not editing)
6. Down → Block C selected
```

### Scenario 2: Create New Block
```
1. [Editing] Block A: "hello|"  (cursor at end)
2. Enter → Creates Block B below, edit mode, cursor at 0
3. Type "world"
4. Escape → Block B selected
5. Up → Block A selected
6. Enter → Edit mode in Block A, cursor at end (NOT creating new block!)
```

---

## Why This Matters

**Without these fixes:**
- Enter on selected block creates unwanted empty blocks
- Escape loses context (can't quickly navigate after editing)
- Navigation flow is broken (can't easily go back to editing same block)

**With these fixes:**
- Enter = "edit this block"
- Escape = "done editing, keep block selected"
- Natural back-and-forth between viewing and editing

---

## Implementation Priority

**P0 - CRITICAL**: These are fundamental navigation behaviors that users expect from Logseq.

Fix order:
1. Escape while editing → select block (most jarring when missing)
2. Enter on selected block → enter edit mode (very confusing to create new blocks)

---

## Testing Checklist

- [ ] Select block, press Enter → edit mode in that block, cursor at end
- [ ] Edit block, press Escape → block becomes selected (not editing)
- [ ] Selected block, press Escape → selection cleared
- [ ] Edit block, press Enter at end → creates new block below
- [ ] Selected block, press Down/Up → navigates without entering edit
