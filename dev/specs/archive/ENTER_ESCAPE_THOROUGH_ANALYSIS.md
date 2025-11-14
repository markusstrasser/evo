# Thorough Analysis: Enter and Escape Behavior

**Date**: 2025-11-13
**Status**: NEEDS VERIFICATION

---

## Current Evo Implementation (After My Changes)

### Enter Key - When Block is Selected (Not Editing)

**Keymap**: `bindings_data.cljc:13`
```clojure
[{:key "Enter"} {:type :enter-edit-selected}]
```

**Intent Handler**: `editing.cljc:57-71`
```clojure
(intent/register-intent! :enter-edit-selected
  {:doc "Enter edit mode in selected block at end of text (Logseq parity).
         Does NOT create a new block."
   :spec [:map [:type [:= :enter-edit-selected]]]
   :handler (fn [db _intent]
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

**What it does**:
1. Get the currently focused/selected block from `:session/selection` node
2. If a block is selected:
   - Calculate text length of that block
   - Clear selection (set `:nodes`, `:focus`, `:anchor` to empty/nil)
   - Enter edit mode in that block (set `:editing-block-id`)
   - Set cursor position to end of text (`text-length`)

**Result**: Block becomes editable, cursor at end, selection cleared

---

### Escape Key - While Editing

**Keymap**: `bindings_data.cljc:15`
```clojure
[{:key "Escape"} {:type :exit-edit-and-select}]
```

**Intent Handler**: `editing.cljc:42-55`
```clojure
(intent/register-intent! :exit-edit-and-select
  {:doc "Exit edit mode and select the block (Logseq parity).
         This is the default Escape behavior in Logseq."
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
                          :anchor editing-block-id}}])))})
```

**What it does**:
1. Get the currently editing block ID from `:session/ui`
2. If editing a block:
   - Clear editing state (set `:editing-block-id` and `:cursor-position` to nil)
   - Select that block (add to `:nodes`, set as `:focus` and `:anchor`)

**Result**: Exit edit mode, block becomes selected

---

## What I NEED to Verify in Logseq

### ❓ Enter Key - Selected Block Behavior

**Question 1**: When a block is selected (highlighted, not editing) and user presses Enter, what happens?

**Options**:
A. Enters edit mode in that selected block (cursor at end)
B. Enters edit mode in that selected block (cursor at start)
C. Creates new empty block below and enters edit mode there
D. Creates new empty block above and enters edit mode there
E. Something else?

**Where to look**:
- Global keyboard event handlers
- Block component click/select handlers
- Shortcut definitions

**My implementation assumes**: Option A (edit selected block, cursor at end)

---

### ❓ Escape Key - Editing Behavior

**Question 2**: When editing a block and user presses Escape, what happens?

**Options**:
A. Exits edit mode, block becomes selected
B. Exits edit mode, selection is cleared entirely
C. Exits edit mode, focus moves to parent
D. Something else?

**Where to look**:
- `editor.cljs:3897` - `escape-editing` function
- Look at call sites of `escape-editing` to see default `select?` value

**Evidence found so far**:
```clojure
(defn escape-editing
  [& {:keys [select? save-block?]
      :or {save-block? true}}]
  (let [edit-block (state/get-edit-block)]
    (p/do!
     (when save-block? (save-current-block!))
     (if select?
       (when-let [node (some-> (state/get-input) (util/rec-get-node "ls-block"))]
         (state/exit-editing-and-set-selected-blocks! [node]))
       (when (= (:db/id edit-block) (:db/id (state/get-edit-block)))
         (state/clear-edit!))))))
```

**Key question**: What is the DEFAULT value of `select?` when Escape is pressed?
- If `select?` is true by default → selects block (matches my implementation)
- If `select?` is false by default → just clears edit (my implementation is WRONG)

**My implementation assumes**: `select?` is true by default

---

## How to Verify

### Method 1: Test in Actual Logseq
1. Open Logseq
2. Create 3 blocks: A, B, C
3. Click on Block B (selects it, doesn't edit)
4. Press Enter
5. **Observe**: Where is cursor? Which block is being edited?

6. Type "hello"
7. Press Escape
8. **Observe**: Is Block B selected? Or is nothing selected?

### Method 2: Search Logseq Source for Call Sites

**Search for**:
```bash
grep -rn "escape-editing" logseq/src --include="*.cljs"
```

**Look for**:
- How is `escape-editing` called when Escape key is pressed?
- Is `:select? true` or `:select? false` passed?
- Is there a default behavior defined somewhere?

---

## Potential Issues with My Implementation

### Issue 1: Cursor Position on Enter

**My implementation**: `cursor-position text-length` (end of text)

**Possible Logseq behavior**: Cursor might go to START (position 0)?

**Need to verify**: Where does cursor land when entering edit mode from selection?

### Issue 2: Escape Select Behavior

**My implementation**: Always selects the block after escape

**Possible Logseq behavior**:
- Might clear selection entirely
- Might depend on context (how you entered edit mode)
- Might have a config option

**Need to verify**: Default Escape behavior in standard use case

---

## Next Steps

1. **Test in Logseq**: Manually verify both behaviors
2. **Search call sites**: Find where `escape-editing` is invoked
3. **Check keyboard handler**: Find global Escape key handler
4. **Update implementation**: Fix if behaviors don't match
5. **Document findings**: Update this file with verified behavior

---

## Status Markers

- ✅ **VERIFIED**: Confirmed by testing or source code
- ❌ **WRONG**: Implementation doesn't match Logseq
- ❓ **UNKNOWN**: Needs verification
- ⚠️ **ASSUMPTION**: Based on reasonable guess, not verified

**Current status**: ❓ UNKNOWN - Both behaviors need verification
