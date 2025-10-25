# Smart Editing Behaviors - Implementation Spec

**Status:** Not Implemented
**Priority:** High (Core UX)
**Dependencies:** Basic editing (✅ implemented)
**Related Specs:** `expand-collapse-zoom.md`, `structural-editing-interaction-spec.md`

---

## Overview

Smart editing behaviors make block editing feel natural and fluid by implementing context-aware responses to common editing actions. These behaviors handle cursor boundaries, list formatting, markup contexts, and other contextual editing patterns found in Logseq.

**Core Principle:** The editor should "do what I mean" based on cursor position and content context.

---

## 1. Cursor Boundary Navigation

### 1.1 Left Arrow at Block Start

**Current Behavior:**
- Browser default: cursor stays at position 0

**Target Behavior:**
- If cursor at position 0 AND block has previous sibling:
  - Move focus to previous block
  - Place cursor at END of previous block's text
- If no previous sibling: stay at position 0

**Implementation:**

```clojure
;; In components/block.cljs - handle-keydown
(defn handle-arrow-left [e db block-id on-intent]
  (let [target (.-target e)
        selection (.getSelection js/window)
        cursor-pos (.-anchorOffset selection)
        at-start? (= cursor-pos 0)]
    (when at-start?
      (.preventDefault e)
      (when-let [prev-id (nav/get-prev-block db block-id)]
        ;; Navigate to previous block
        (on-intent {:type :navigate-up :block-id block-id})
        ;; After navigation, move cursor to end
        ;; This requires a DOM operation after the next render
        (js/setTimeout
          (fn []
            (when-let [prev-elem (js/document.querySelector
                                   (str "[data-block-id='" prev-id "'] .content-edit"))]
              (let [text-length (.-length (.-textContent prev-elem))
                    range (.createRange js/document)
                    sel (.getSelection js/window)]
                (.setStart range (.-firstChild prev-elem) text-length)
                (.collapse range)
                (.removeAllRanges sel)
                (.addRange sel range))))
          0)))))
```

**Intent:** None needed (navigation handled via existing `:navigate-up`)

**Edge Cases:**
- Empty previous block: cursor goes to position 0
- Previous block has children: cursor goes to end of parent text, NOT last child
- First block in parent: no-op (stays at position 0)

**Test Cases:**
```clojure
;; test/components/block_test.cljs
(deftest left-at-start-navigates
  (let [db (-> (db/empty-db)
               (tx/interpret [{:op :create-node :id "a" :props {:text "First"}}
                              {:op :create-node :id "b" :props {:text "Second"}}]))
        cursor-at-start true]
    ;; Simulate left arrow at start of "b"
    ;; Assert: focus moves to "a", cursor at position 5 (end of "First")
    ))
```

---

### 1.2 Right Arrow at Block End

**Current Behavior:**
- Browser default: cursor stays at end position

**Target Behavior:**
- If cursor at end of text AND block has next sibling:
  - Move focus to next block
  - Place cursor at START (position 0) of next block's text
- If no next sibling: stay at end

**Implementation:**

```clojure
(defn handle-arrow-right [e db block-id on-intent]
  (let [target (.-target e)
        text-content (.-textContent target)
        text-length (.-length text-content)
        selection (.getSelection js/window)
        cursor-pos (.-anchorOffset selection)
        at-end? (= cursor-pos text-length)]
    (when at-end?
      (.preventDefault e)
      (when-let [next-id (nav/get-next-block db block-id)]
        (on-intent {:type :navigate-down :block-id block-id})
        (js/setTimeout
          (fn []
            (when-let [next-elem (js/document.querySelector
                                   (str "[data-block-id='" next-id "'] .content-edit"))]
              (let [range (.createRange js/document)
                    sel (.getSelection js/window)]
                (.setStart range (.-firstChild next-elem) 0)
                (.collapse range)
                (.removeAllRanges sel)
                (.addRange sel range))))
          0)))))
```

**Intent:** None needed (uses `:navigate-down`)

**Edge Cases:**
- Empty next block: cursor goes to position 0
- Last block in parent: no-op
- Multi-line text: only triggers at END of last line

---

### 1.3 Delete Key at Block End

**Current Behavior:**
- Not implemented (browser default: no-op at end)

**Target Behavior:**
- If cursor at end of text AND block has next sibling:
  - Merge current block with next block
  - Text becomes: `current_text + next_text`
  - Cursor stays at merge point (end of current text)
  - Delete next block
- If no next sibling: no-op

**Implementation:**

```clojure
;; New intent in plugins/editing.cljc
(defintent :merge-with-next
  {:sig [db {:keys [block-id]}]
   :doc "Merge block with next sibling, delete next block."
   :spec [:map [:type [:= :merge-with-next]] [:block-id :string]]
   :ops (let [next-id (get-in db [:derived :next-id-of block-id])
              curr-text (get-block-text db block-id)
              next-text (get-block-text db next-id)
              merged-text (str curr-text next-text)]
          (when next-id
            [{:op :update-node :id block-id :props {:text merged-text}}
             {:op :place :id next-id :under const/root-trash :at :last}]))})

;; In handle-keydown
(defn handle-delete [e db block-id on-intent]
  (let [target (.-target e)
        text-content (.-textContent target)
        selection (.getSelection js/window)
        cursor-pos (.-anchorOffset selection)
        at-end? (= cursor-pos (.-length text-content))]
    (when at-end?
      (.preventDefault e)
      (on-intent {:type :merge-with-next :block-id block-id}))))
```

**Intent:** `:merge-with-next` (new)

**Edge Cases:**
- Next block has children: children become children of current block
- Next block is last: no-op
- Empty next block: just deletes it

**Migration of Children:**
```clojure
:ops (let [next-id (get-in db [:derived :next-id-of block-id])
           next-children (get-in db [:children-by-parent next-id] [])
           curr-text (get-block-text db block-id)
           next-text (get-block-text db next-id)
           merged-text (str curr-text next-text)]
       (when next-id
         (concat
           [{:op :update-node :id block-id :props {:text merged-text}}]
           ;; Move next block's children to current block
           (map (fn [child-id]
                  {:op :place :id child-id :under block-id :at :last})
                next-children)
           ;; Delete next block
           [{:op :place :id next-id :under const/root-trash :at :last}])))
```

---

## 2. List Item Behaviors

### 2.1 Empty List Item → Unformat

**Behavior:**
- User creates list item (markdown: `- `, `* `, `1. `)
- User presses Enter WITHOUT typing content
- Result: Remove list formatting, create plain block

**Detection:**
```clojure
(defn list-marker? [text]
  "Check if text starts with list marker"
  (re-matches #"^([-*+]|\d+\.)\s*$" text))

;; In handle-enter
(when (list-marker? (.-textContent target))
  ;; Remove list marker, create plain block
  (.preventDefault e)
  (on-intent {:type :update-content
              :block-id block-id
              :text ""})
  ;; Don't create new block
  )
```

**Intent:** Use existing `:update-content`

**Edge Cases:**
- Numbered list: reset to plain block (don't decrement number)
- Nested list: stay at same indent level
- Task list `- [ ]`: also removes checkbox

---

### 2.2 Auto-Increment Numbered Lists

**Behavior:**
- Block starts with `1. Some text`
- User presses Enter
- New block auto-populates with `2. ` (cursor after space)

**Implementation:**
```clojure
(defn extract-list-number [text]
  "Extract number from numbered list marker. Returns nil if not numbered."
  (when-let [match (re-matches #"^(\d+)\.\s.*" text)]
    (js/parseInt (second match))))

(defn handle-enter [e db block-id on-intent]
  (let [target (.-target e)
        text (.-textContent target)
        list-num (extract-list-number text)]
    (if list-num
      ;; Numbered list - create next item with incremented number
      (let [next-num (inc list-num)
            parent (get-in db [:derived :parent-of block-id])
            new-id (str "block-" (random-uuid))]
        (.preventDefault e)
        (on-intent {:type :create-and-place
                    :id new-id
                    :parent parent
                    :after block-id
                    :initial-text (str next-num ". ")}))
      ;; Plain block - normal split behavior
      (handle-enter-default e db block-id on-intent))))
```

**Intent:** Extend `:create-and-place` to support `:initial-text` param

```clojure
;; In plugins/struct.cljc
(defintent :create-and-place
  {:sig [db {:keys [id parent after initial-text]}]
   :spec [:map
          [:type [:= :create-and-place]]
          [:id :string]
          [:parent :string]
          [:after {:optional true} :string]
          [:initial-text {:optional true} :string]]
   :ops [{:op :create-node
          :id id
          :type :block
          :props {:text (or initial-text "")}}
         {:op :place :id id :under parent :at (if after {:after after} :last)}]})
```

**Edge Cases:**
- Split within numbered item: preserve number on first part, increment on second
- Empty numbered item: remove formatting (see 2.1)
- Multi-digit numbers: `10. ` → `11. `

---

### 2.3 Checkbox Toggling

**Behavior:**
- Block contains `[ ]` or `[x]` (task checkbox)
- Click checkbox OR use Mod+Enter → toggle state
- `[ ]` ↔ `[x]`

**Implementation:**
```clojure
(defn toggle-checkbox [text]
  "Toggle checkbox in text. Returns updated text."
  (cond
    (str/includes? text "[ ]")
    (str/replace-first text "[ ]" "[x]")

    (str/includes? text "[x]")
    (str/replace-first text "[x]" "[ ]")

    :else text))

;; New intent
(defintent :toggle-checkbox
  {:sig [db {:keys [block-id]}]
   :doc "Toggle checkbox state in block text."
   :spec [:map [:type [:= :toggle-checkbox]] [:block-id :string]]
   :ops (let [text (get-block-text db block-id)
              new-text (toggle-checkbox text)]
          [{:op :update-node :id block-id :props {:text new-text}}])})
```

**Keymap:**
```clojure
;; In bindings_data.cljc
:editing [[{:key "Enter" :mod true} :toggle-checkbox]]
:non-editing [[{:key "Enter" :mod true} :toggle-checkbox]]
```

**Edge Cases:**
- Multiple checkboxes in one block: only toggle first
- Checkbox not at start: still toggles (Logseq behavior)
- `[X]` (capital): normalize to lowercase `[x]`

---

## 3. Smart Enter Contexts

### 3.1 Document Mode vs Outliner Mode

**Not implementing initially** - this is a mode toggle, requires UI state.

**Future Consideration:**
- Document mode: Enter → newline, Shift+Enter → new block
- Outliner mode: Enter → new block, Shift+Enter → newline
- Default: Outliner mode (current behavior)

---

### 3.2 Enter Inside Markup

**Behavior:**
- Cursor inside `**bold**`, `*italic*`, `` `code` ``
- Press Enter → cursor moves AFTER closing delimiter

**Detection:**
```clojure
(defn cursor-in-markup? [text cursor-pos]
  "Returns markup type if cursor is inside markup, else nil"
  (let [before (subs text 0 cursor-pos)
        after (subs text cursor-pos)]
    (cond
      (and (str/ends-with? before "**")
           (str/starts-with? after "**"))
      :bold

      (and (str/ends-with? before "*")
           (str/starts-with? after "*"))
      :italic

      (and (str/ends-with? before "`")
           (str/starts-with? after "`"))
      :code

      :else nil)))

(defn find-closing-delimiter [text cursor-pos markup-type]
  "Find position of closing delimiter after cursor"
  (let [after (subs text cursor-pos)
        delimiter (case markup-type
                    :bold "**"
                    :italic "*"
                    :code "`")]
    (when-let [idx (str/index-of after delimiter)]
      (+ cursor-pos idx (count delimiter)))))

;; In handle-enter
(when-let [markup (cursor-in-markup? text cursor-pos)]
  (.preventDefault e)
  ;; Move cursor past closing delimiter
  (when-let [new-pos (find-closing-delimiter text cursor-pos markup)]
    (move-cursor-to-position! target new-pos)))
```

**Not creating new block** - just moves cursor. This is UX polish.

**Priority:** Low (nice-to-have)

---

### 3.3 Enter on Block Reference

**Behavior:**
- Cursor on `((block-ref-uuid))`
- Press Enter → open referenced block in sidebar

**Requires:** Block reference implementation (Phase 2)

**Intent:** `:open-block-in-sidebar` (future)

---

## 4. Properties Drawer Behavior

**Logseq Feature:** Org-mode style properties

```
:PROPERTIES:
:id: 12345
:custom: value
:END:
```

**Status:** Not implementing initially

**Reason:** Properties are a separate feature. Current evo uses inline metadata.

**Future:** If properties added, implement:
- Enter in `:PROPERTIES:` → add property line with `:`
- Enter on `:END:` → create block below drawer

---

## 5. Code Block Behavior

### 5.1 Triple-Backtick Trigger

**Behavior:**
- User types ` ```language`
- Triggers language picker autocomplete
- On select → creates code block with syntax highlighting

**Implementation Phases:**

**Phase 1: Basic Detection**
```clojure
(defn code-block-trigger? [text]
  (re-matches #"^```(\w*)$" text))

;; In handle-input (new event - not just keydown)
(when (code-block-trigger? new-text)
  ;; Show autocomplete with common languages
  (on-intent {:type :show-autocomplete
              :trigger :code-block
              :query (second (re-matches #"^```(\w*)$" new-text))}))
```

**Phase 2: CodeMirror Integration** (Future)
- Enter on code block → open CodeMirror editor
- Syntax highlighting
- Full editor features

**Priority:** Medium (useful but not critical)

---

## 6. Implementation Checklist

### High Priority (Phase 1)
- [ ] Left arrow at start → navigate to prev block (end cursor)
- [ ] Right arrow at end → navigate to next block (start cursor)
- [ ] Delete at end → merge with next
- [ ] Empty list item → remove formatting
- [ ] Auto-increment numbered lists
- [ ] Checkbox toggling (Mod+Enter)

### Medium Priority (Phase 2)
- [ ] Enter inside markup → exit markup
- [ ] Code block trigger (` ```language`)
- [ ] Smart split for numbered lists

### Low Priority (Future)
- [ ] Document mode toggle
- [ ] Properties drawer behavior
- [ ] CodeMirror integration

---

## 7. Testing Strategy

### Unit Tests
```clojure
;; test/plugins/editing_smart_test.cljc
(deftest left-arrow-at-start
  ;; Setup: two blocks "a" and "b"
  ;; Action: left arrow at position 0 in "b"
  ;; Assert: focus on "a", cursor at end
  )

(deftest delete-at-end-merges
  ;; Setup: block "a" with text "Hello", block "b" with text "World"
  ;; Action: delete at end of "a"
  ;; Assert: "a" text is "HelloWorld", "b" is deleted
  )

(deftest empty-list-item-unformats
  ;; Setup: block with text "- "
  ;; Action: Enter
  ;; Assert: block text is "", no new block created
  )
```

### Integration Tests
```clojure
;; test/integration/smart_editing_test.cljs
(deftest full-list-workflow
  ;; 1. Type "1. First item"
  ;; 2. Press Enter
  ;; 3. Assert: new block with "2. "
  ;; 4. Press Enter immediately (empty item)
  ;; 5. Assert: list formatting removed
  )
```

### Manual Testing Checklist
- [ ] Navigate between blocks with left/right arrows
- [ ] Merge blocks with Delete key
- [ ] Create numbered list, verify auto-increment
- [ ] Create empty list item, verify unformatting
- [ ] Toggle checkboxes with Mod+Enter
- [ ] Test with nested blocks
- [ ] Test with empty blocks
- [ ] Test with multi-line content

---

## 8. Migration Notes

**Backward Compatibility:** All new behaviors are additive - no breaking changes to existing functionality.

**Data Migration:** None required - all behaviors operate on existing block text content.

**User Impact:**
- Immediate UX improvement
- Matches Logseq muscle memory
- No learning curve for existing users

---

## 9. Performance Considerations

**DOM Operations:**
- Cursor positioning uses `setTimeout` to defer until next render
- This is necessary for React/Replicant reconciliation
- Performance impact: negligible (single RAF frame)

**Intent Compilation:**
- Merge operations generate 1-3 ops
- List detection uses simple regex (fast)
- No complex tree traversal

**Optimizations:**
- Cache regex patterns (already compiled in ClojureScript)
- Batch cursor operations where possible
- Use event delegation for click handlers

---

## 10. Open Questions

1. **Numbered list reset:** Should Backspace on "1. " reset to plain block or go to previous block?
   - **Decision:** Reset to plain block (matches empty list behavior)

2. **Multi-line list items:** How to handle Enter in middle of multi-line numbered item?
   - **Decision:** Split text, preserve number on first part, increment on second

3. **Checkbox position:** Only at start of block or anywhere?
   - **Decision:** Anywhere (Logseq behavior), but toggle first occurrence only

4. **Delete with children:** When merging blocks, what happens to next block's children?
   - **Decision:** Children migrate to current block (see 1.3 implementation)

---

## 11. References

- Logseq source: `~/Projects/best/logseq/src/main/frontend/handler/editor.cljs`
- Cursor utilities: `~/Projects/best/logseq/src/main/frontend/util/cursor.cljs`
- Current implementation: `src/components/block.cljs:130-141` (merge-with-prev)
- Related spec: `.architect/specs/structural-editing-interaction-spec.md`
