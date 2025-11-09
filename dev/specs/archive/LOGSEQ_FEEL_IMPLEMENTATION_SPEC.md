# Logseq-Feel Navigation & Editing Implementation Spec

**Goal:** Achieve Logseq's fluid, continuous-document editing feel while respecting Evo's plugin architecture.

## Architecture Mapping: Logseq → Evo

### Core Difference
**Logseq:** Monolithic handlers with direct DOM manipulation and imperative state updates.
**Evo:** Intent-based plugin system where operations are declared and applied via transaction pipeline.

### Where Things Live

| Concern | Logseq Location | Evo Location | Notes |
|---------|----------------|--------------|-------|
| Cursor row detection | `util/cursor.cljs` (mock-text technique) | `components/block.cljs` | Already implemented, needs enhancement |
| Arrow key handling | `handler/editor.cljs:keydown-up-down-handler` | `components/block.cljs:handle-arrow-*` | Needs cursor memory |
| Cross-boundary navigation | `handler/editor.cljs:move-cross-boundary-up-down` | **NEW: `plugins/navigation.cljc`** | NEW PLUGIN |
| Cursor position calculation | `util.cljc:get-line-pos` | **NEW: utility in navigation plugin** | Port this logic |
| Block editing entry point | `handler/block.cljs:edit-block!` | Intent: `:enter-edit` | Needs cursor position support |
| Smart Enter behaviors | `handler/editor.cljs` (scattered) | **ENHANCE: `plugins/smart_editing.cljc`** | Extend existing plugin |
| Move while editing | `handler/editor.cljs:move-up-down` | **NEW: intent in `plugins/struct.cljc`** | Currently disabled in edit mode |

---

## Component 1: Cursor Position Tracking (NEW PLUGIN)

**File:** `src/plugins/navigation.cljc`

### Purpose
Track horizontal cursor position (column) when navigating vertically between blocks, enabling Logseq's "continuous document" feel.

### Data Structure

```clojure
;; Store in :ui (ephemeral session state)
{:ui
 {:cursor-memory
  {:line-pos 15           ; Column position (grapheme count from line start)
   :last-block-id "abc"   ; Which block we were in
   :direction :down}}}    ; Last navigation direction
```

### Key Functions

#### 1. Calculate Line Position (Port from Logseq)

```clojure
(defn get-line-pos
  "Calculate horizontal cursor position within current line.

   Returns grapheme count from start of current line to cursor.
   Handles multi-byte characters (emojis) correctly.

   Example:
     Text: 'hello\\nwo|rld'  (| = cursor)
     Returns: 2  (cursor is 2 chars into 'world' line)"
  [text cursor-pos]
  ;; Port logic from logseq/util.cljc:389
  ;; Uses grapheme-splitter for emoji support
  )
```

#### 2. Calculate Target Position

```clojure
(defn get-target-cursor-pos
  "Calculate where cursor should land in target block.

   Uses mock-text technique to find character at same horizontal position.
   Falls back to end-of-line if target line is shorter.

   Args:
     target-text: Text of target block
     line-pos: Desired column position (from cursor memory)
     direction: :up or :down

   Returns: Cursor position (integer offset into target-text)"
  [target-text line-pos direction]
  ;; Logic similar to cursor.cljs:next-cursor-pos-up-down
  ;; But simpler - just find position on first/last line
  )
```

### Intent: `:navigate-with-cursor-memory`

```clojure
(intent/register-intent! :navigate-with-cursor-memory
  {:doc "Navigate to adjacent block, preserving cursor column position.

         This is the PRIMARY navigation intent while editing.
         Replaces simple :selection :mode :prev/:next when cursor memory is desired."

   :spec [:map
          [:type [:= :navigate-with-cursor-memory]]
          [:direction [:enum :up :down]]
          [:current-block-id :string]
          [:current-text :string]
          [:current-cursor-pos :int]]

   :handler
   (fn [db {:keys [direction current-block-id current-text current-cursor-pos]}]
     (let [;; Calculate and store cursor memory
           line-pos (get-line-pos current-text current-cursor-pos)

           ;; Find target block
           target-id (case direction
                       :up (get-in db [:derived :prev-id-of current-block-id])
                       :down (get-in db [:derived :next-id-of current-block-id]))

           ;; If no sibling, just stay in current block
           _ (when-not target-id (return nil))

           target-text (get-in db [:nodes target-id :props :text] "")

           ;; Calculate target cursor position
           target-pos (get-target-cursor-pos target-text line-pos direction)

           ;; Determine cursor position mode (:start, :end, or specific pos)
           cursor-at (cond
                       (zero? target-pos) :start
                       (>= target-pos (count target-text)) :end
                       :else target-pos)]

       ;; Update cursor memory (in :ui, ephemeral)
       [{:op :update-node
         :id const/session-ui-id
         :props {:cursor-memory {:line-pos line-pos
                                 :last-block-id current-block-id
                                 :direction direction}}}

        ;; Exit edit on current block
        {:op :update-node
         :id const/session-ui-id
         :props {:editing-block-id nil}}

        ;; Enter edit on target block WITH cursor position
        {:op :update-node
         :id const/session-ui-id
         :props {:editing-block-id target-id
                 :cursor-position cursor-at}}]))})
```

### Integration Point: `components/block.cljs`

**Modify:** `handle-arrow-up` and `handle-arrow-down`

```clojure
(defn handle-arrow-up [e db block-id on-intent]
  (let [target (.-target e)
        cursor-pos (detect-cursor-row-position target)]
    (cond
      (has-text-selection?)
      (let [selection (.getSelection js/window)]
        (.preventDefault e)
        (.collapseToStart selection))

      (:first-row? cursor-pos)
      (do (.preventDefault e)
          ;; NEW: Use cursor-memory navigation instead of simple :prev
          (let [text-content (.-textContent target)
                selection (.getSelection js/window)
                cursor-offset (.-anchorOffset selection)]
            (on-intent {:type :navigate-with-cursor-memory
                        :direction :up
                        :current-block-id block-id
                        :current-text text-content
                        :current-cursor-pos cursor-offset})))

      :else nil)))
```

---

## Component 2: Enhanced Enter Key (EXTEND EXISTING)

**File:** `src/plugins/smart_editing.cljc`

### New Intent: `:smart-split`

```clojure
(intent/register-intent! :smart-split
  {:doc "Context-aware block splitting on Enter.

         Behaviors:
         - Empty list marker → unformat to plain block
         - Numbered list → increment number for new block
         - Checkbox → continue checkbox pattern
         - Otherwise → simple split"

   :spec [:map
          [:type [:= :smart-split]]
          [:block-id :string]
          [:cursor-pos :int]]

   :handler
   (fn [db {:keys [block-id cursor-pos]}]
     (let [text (get-block-text db block-id)
           before (subs text 0 cursor-pos)
           after (subs text cursor-pos)]

       (cond
         ;; Empty list marker - just unformat
         (and (empty? after) (list-marker? before))
         [{:op :update-node :id block-id :props {:text ""}}]

         ;; Numbered list - increment
         (extract-list-number before)
         (let [num (extract-list-number before)
               new-text (str (inc num) ". " after)]
           (split-and-create-ops db block-id before new-text))

         ;; Checkbox - continue pattern
         (checkbox-pattern? before)
         (let [new-text (str "[ ] " after)]
           (split-and-create-ops db block-id before new-text))

         ;; Default split
         :else
         (split-and-create-ops db block-id before after))))})
```

### Integration: `components/block.cljs`

```clojure
(defn handle-enter [e db block-id on-intent]
  (.preventDefault e)
  (let [selection (.getSelection js/window)
        cursor-pos (.-anchorOffset selection)]
    ;; Use :smart-split instead of :split-at-cursor
    (on-intent {:type :smart-split
                :block-id block-id
                :cursor-pos cursor-pos})))
```

---

## Component 3: Backspace Merge with Cursor Preservation (ENHANCE EXISTING)

**File:** `src/plugins/editing.cljc`

### Enhance: `:merge-with-prev`

```clojure
(intent/register-intent! :merge-with-prev
  {:doc "Merge block with previous sibling, placing cursor at merge point."
   :spec [:map
          [:type [:= :merge-with-prev]]
          [:block-id :string]]
   :handler
   (fn [db {:keys [block-id]}]
     (let [prev-id (get-in db [:derived :prev-id-of block-id])
           prev-text (get-block-text db prev-id)
           curr-text (get-block-text db block-id)
           merged-text (str prev-text curr-text)
           ;; KEY: Calculate where cursor should land (end of prev text)
           cursor-at (count prev-text)]
       (when prev-id
         [{:op :update-node :id prev-id :props {:text merged-text}}
          {:op :place :id block-id :under const/root-trash :at :last}
          ;; NEW: Store cursor position for entering prev block
          {:op :update-node
           :id const/session-ui-id
           :props {:editing-block-id prev-id
                   :cursor-position cursor-at}}])))})
```

---

## Component 4: Move While Editing (ENHANCE EXISTING)

**File:** `src/plugins/struct.cljc`

### New Intents: `:move-block-up-while-editing`, `:move-block-down-while-editing`

```clojure
(intent/register-intent! :move-block-up-while-editing
  {:doc "Move current editing block up, preserving edit mode."
   :spec [:map
          [:type [:= :move-block-up-while-editing]]
          [:block-id :string]]
   :handler
   (fn [db {:keys [block-id]}]
     ;; Use existing move-selected-up logic but don't exit edit mode
     ;; Just return the structural move ops
     (move-selected-up-ops db))})

(intent/register-intent! :move-block-down-while-editing
  {:doc "Move current editing block down, preserving edit mode."
   :spec [:map
          [:type [:= :move-block-down-while-editing]]
          [:block-id :string]]
   :handler
   (fn [db {:keys [block-id]}]
     (move-selected-down-ops db))})
```

### Keymap Update: `src/keymap/bindings_data.cljc`

```clojure
{:editing [[;; ... existing bindings ...

            ;; NEW: Allow block movement while editing
            [{:key "ArrowUp" :shift true :mod true} :move-block-up-while-editing]
            [{:key "ArrowDown" :shift true :mod true} :move-block-down-while-editing]
            [{:key "ArrowUp" :shift true :alt true} :move-block-up-while-editing]
            [{:key "ArrowDown" :shift true :alt true} :move-block-down-while-editing]]]}
```

---

## Component 5: Delete Forward (Merge with Next)

**File:** `src/plugins/smart_editing.cljc`

### Already Exists: `:merge-with-next`

This is already implemented! Just need to wire up to Delete key.

### Keymap Update: `src/keymap/bindings_data.cljc`

```clojure
{:editing [[;; ... existing ...

            ;; NEW: Delete at end merges with next block
            [{:key "Delete"} :merge-with-next]]]}
```

**NOTE:** Need component-level check - only trigger if at end of text.

### Component Integration: `components/block.cljs`

```clojure
(defn handle-delete [e db block-id on-intent]
  "Handle Delete key - merge with next block if at end."
  (let [target (.-target e)
        text-content (.-textContent target)
        selection (.getSelection js/window)
        is-at-end (= (.-anchorOffset selection) (count text-content))]
    (when is-at-end
      (.preventDefault e)
      (on-intent {:type :merge-with-next :block-id block-id}))))

;; Add to handle-keydown
(defn handle-keydown [e db block-id on-intent]
  (let [key (.-key e)
        ;; ... modifiers ...
        ]
    (cond
      ;; ... existing handlers ...

      ;; NEW: Delete at end
      (and (= key "Delete") (not shift?) (not mod?) (not alt?))
      (handle-delete e db block-id on-intent)

      ;; ...
      )))
```

---

## Edge Cases & Context Details

### 1. First/Last Row Detection - Multi-line Blocks

**Problem:** Mock-text technique requires updated content.

**Solution:** Already handled! `update-mock-text!` is called on every input event and render.

**Test Case:**
```
Block content:
"Line one
Line two
Line three|"

Press Up → should move within block (to "Line two")
Press Up again → should move to previous block
```

### 2. Empty Blocks

**Context:** Cursor position is always 0 in empty block.

**Behavior:**
- Up on empty block → Navigate to prev block, cursor at END
- Down on empty block → Navigate to next block, cursor at START

**Test Case:**
```
[Block A: "hello world"]
[Block B: ""]          <-- editing this
[Block C: "foo bar"]

Up: Should land at end of "hello world" (pos 11)
Down: Should land at start of "foo bar" (pos 0)
```

### 3. Selection Exists - Don't Navigate

**Rule:** If user has text selected, arrows should collapse selection, NOT navigate.

**Current implementation:** ✅ Already correct in `handle-arrow-up/down`

### 4. Backspace on Empty Block

**Current:** Deletes block, navigates to prev.

**Logseq:** Same behavior.

**Test Case:**
```
[Block A: "hello"]
[Block B: ""]      <-- editing, press Backspace

Result: Block B deleted, editing Block A at END
```

### 5. Numbered Lists - Increment on Enter

**Context:** Detect pattern `^(\d+)\. (.*)$`

**Behavior:**
- `1. hello|` → Enter → New block `2. `, cursor after space
- `1. |` (empty list) → Enter → Unformat to plain block

**Test Cases:**
```clojure
(deftest enter-on-numbered-list
  (let [db (-> (sample-db)
               (assoc-in [:nodes "a" :props :text] "1. First item"))
        result (intent/handle db {:type :smart-split
                                  :block-id "a"
                                  :cursor-pos 13})]  ; End of "1. First item"
    ;; Should create new block "2. "
    (is (= "1. First item" (get-in result [:nodes "a" :props :text])))
    (is (string/starts-with? (get-in result [:nodes new-id :props :text]) "2. "))))

(deftest enter-on-empty-list-marker
  (let [db (-> (sample-db)
               (assoc-in [:nodes "a" :props :text] "1. "))
        result (intent/handle db {:type :smart-split
                                  :block-id "a"
                                  :cursor-pos 3})]  ; After "1. "
    ;; Should unformat - just clear text
    (is (= "" (get-in result [:nodes "a" :props :text])))))
```

### 6. Checkboxes - Continue Pattern

**Context:** Pattern `\[ \]` or `\[x\]` at line start

**Behavior:**
- `[ ] Task|` → Enter → New block `[ ] `, cursor after space
- `[ ] |` (empty checkbox) → Enter → Unformat

**Test Case:**
```clojure
(deftest enter-on-checkbox
  (let [db (-> (sample-db)
               (assoc-in [:nodes "a" :props :text] "[ ] Todo item"))
        result (intent/handle db {:type :smart-split
                                  :block-id "a"
                                  :cursor-pos 14})]
    (is (= "[ ] Todo item" (get-in result [:nodes "a" :props :text])))
    (is (string/starts-with? (get-in result [:nodes new-id :props :text]) "[ ] "))))
```

### 7. Cursor Position Notation

**Logseq uses:**
- Integer: Specific character position
- `:max`: End of block
- `[:up pos]` or `[:down pos]`: Direction + line position (for cursor memory)
- `0`: Start of block

**Evo should use:**
- Integer: Specific position
- `:start`: Beginning
- `:end`: End
- Store direction + line-pos separately in `:cursor-memory`

---

## Testing Strategy

### Unit Tests (Clojure, REPL-testable)

**File:** `test/plugins/navigation_test.cljc`

```clojure
(deftest get-line-pos-test
  (testing "Simple single line"
    (is (= 5 (nav/get-line-pos "hello world" 5))))

  (testing "Multi-line - cursor on second line"
    (is (= 3 (nav/get-line-pos "line one\nline two" 12))))  ; "line one\nlin|"

  (testing "With emoji"
    (is (= 2 (nav/get-line-pos "hi 😀 there" 5)))))  ; Emoji counts as 1

(deftest get-target-cursor-pos-test
  (testing "Target longer than line-pos"
    (is (= 5 (nav/get-target-cursor-pos "hello world" 5 :down))))

  (testing "Target shorter - fall back to end"
    (is (= 3 (nav/get-target-cursor-pos "hi" 10 :down)))))

(deftest navigate-with-cursor-memory-test
  (testing "Navigate down with cursor memory"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "hello world")
                 (assoc-in [:nodes "b" :props :text] "foo bar baz"))
          ops (intent/handle db {:type :navigate-with-cursor-memory
                                 :direction :down
                                 :current-block-id "a"
                                 :current-text "hello world"
                                 :current-cursor-pos 6})]  ; After "hello "
      ;; Should store line-pos in cursor-memory
      (is (= 6 (get-in ops [0 :props :cursor-memory :line-pos])))
      ;; Should enter block "b" at position 6 (after "foo ba")
      (is (= 6 (get-in ops [2 :props :cursor-position]))))))
```

### Integration Tests (Component-level, Browser)

**File:** `test/components/block_navigation_test.cljs`

Use REPL component testing:

```clojure
(repl/test-component! 'components.block/Block
  {:db (-> (repl/sample-db!)
           (assoc-in [:nodes "a" :props :text] "first\nline two")
           (assoc-in [:nodes "b" :props :text] "second"))
   :block-id "a"
   :depth 0
   :on-intent identity})

;; Manually:
;; 1. Click to edit
;; 2. Position cursor mid-line on "line two"
;; 3. Press Down
;; 4. Verify cursor lands at same column in "second"
```

### E2E Tests (Port from Logseq)

**File:** `test/e2e/navigation_test.clj` (using chrome-devtools MCP)

```clojure
(deftest arrow-navigation-preserves-cursor
  (testing "Cursor column preserved when navigating between blocks"
    ;; Create two blocks
    (create-blocks ["hello world" "foo bar baz"])
    ;; Click to edit first block
    (click-block 0)
    ;; Position cursor after "hello " (pos 6)
    (set-cursor-pos 6)
    ;; Press Down
    (press-key "ArrowDown")
    ;; Verify cursor in second block at pos 6 (after "foo ba")
    (is (= 6 (get-cursor-pos)))))

(deftest enter-continues-numbered-list
  (testing "Enter on numbered list increments number"
    (create-block "1. First item")
    (click-block 0)
    (move-cursor-to-end)
    (press-key "Enter")
    (is (= "2. " (get-edit-content)))))
```

---

## Implementation Order

### Phase 1: Foundation (Cursor Memory)
1. Create `src/plugins/navigation.cljc`
2. Port `get-line-pos` utility
3. Implement `get-target-cursor-pos`
4. Add `:navigate-with-cursor-memory` intent
5. Update `components/block.cljs` arrow handlers
6. Write unit tests

**Test:** Arrow navigation preserves column position

### Phase 2: Smart Enter
1. Enhance `plugins/smart_editing.cljc`
2. Add pattern detection helpers
3. Implement `:smart-split` intent
4. Update `handle-enter` in `components/block.cljs`
5. Write unit tests

**Test:** Enter continues lists/checkboxes

### Phase 3: Backspace/Delete Merge
1. Enhance `:merge-with-prev` with cursor position
2. Add Delete key handler
3. Update component handlers
4. Write tests

**Test:** Cursor lands at merge point

### Phase 4: Move While Editing
1. Add `:move-block-*-while-editing` intents
2. Update keymap for `:editing` context
3. Ensure edit mode is preserved
4. Write tests

**Test:** Block moves without losing edit mode

### Phase 5: Polish & E2E
1. Port Logseq's E2E tests
2. Test all edge cases
3. Verify mock-text accuracy
4. Performance check (cursor memory lookup)

---

## Performance Considerations

### Cursor Memory Lookup
- **Current:** O(1) map lookup in `:ui`
- **Concern:** None - ephemeral state, single entry

### Mock-text Updates
- **Current:** On every input event
- **Concern:** Could be expensive for very long blocks
- **Mitigation:** Already throttled by browser input events, < 1000 chars assumption

### Line Position Calculation
- **Current:** Uses grapheme-splitter library
- **Concern:** Emoji handling overhead
- **Mitigation:** Only called on boundary navigation (infrequent)

---

## Open Questions

### Q: Should cursor memory persist across non-adjacent navigations?

**Logseq:** Cursor memory is fresh on each navigation (recalculated).

**Evo:** Same approach - calculate line-pos at navigation time, don't persist across actions.

### Q: What if user clicks to a different block (not arrow navigation)?

**Answer:** Cursor memory is irrelevant. On click, enter edit at click position. Cursor memory only applies to arrow-based navigation.

### Q: Undo/Redo cursor position?

**Logseq:** Doesn't restore cursor position on undo.

**Evo:** Same - cursor position is NOT part of history. Only structural changes are undoable.

---

## Success Criteria

✅ **Arrow Up/Down at block boundaries preserves cursor column**
✅ **Enter on numbered list increments number**
✅ **Enter on checkbox continues pattern**
✅ **Empty list marker + Enter → unformat**
✅ **Backspace merge places cursor at merge point**
✅ **Delete at end merges with next block**
✅ **Can move blocks (Cmd+Shift+Arrow) while editing**
✅ **All Logseq E2E navigation tests pass**

---

## References

**Logseq Source Files:**
- `src/main/frontend/util/cursor.cljs` - Mock-text cursor detection
- `src/main/frontend/util.cljc:389` - Line position calculation
- `src/main/frontend/handler/editor.cljs:2679` - Arrow navigation handler
- `src/main/frontend/handler/editor.cljs:2628` - Cross-boundary navigation
- `src/main/frontend/handler/block.cljs:99` - Text range calculation for cursor positioning
- `clj-e2e/test/logseq/e2e/outliner_basic_test.clj` - Navigation E2E tests

**Evo Files to Modify:**
- `src/plugins/navigation.cljc` - NEW
- `src/plugins/smart_editing.cljc` - ENHANCE
- `src/plugins/editing.cljc` - ENHANCE (merge cursor pos)
- `src/plugins/struct.cljc` - ADD (move while editing)
- `src/components/block.cljs` - MODIFY (handlers)
- `src/keymap/bindings_data.cljc` - ADD (editing keybinds)
- `test/plugins/navigation_test.cljc` - NEW
- `test/components/block_navigation_test.cljs` - NEW
