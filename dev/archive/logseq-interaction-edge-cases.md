# Logseq Interaction Edge Cases - Cursor Position & Key Press Behaviors

Research findings from Logseq codebase (`~/Projects/best/logseq`) examining cursor positions and interaction states.

---

## 1. Selection State + Key Press Behaviors

### 1.1 Enter with Selection
**State before:** Blocks selected (blue highlight), not editing
**Action:** Press Enter
**What happens:** No special handler found in selection mode for Enter. Selection mode handlers focus on Tab, arrow keys, copy/cut/delete.
**Code location:** `/src/main/frontend/handler/editor.cljs:1849-1853`
**Behavior:** Tab handler checks `(state/selection?)` and calls `on-tab` which indents/outdents all selected blocks.

```clojure
(defn on-tab
  "`direction` = :left | :right."
  [direction]
  (let [blocks (get-selected-ordered-blocks)]
    (block-handler/indent-outdent-blocks! blocks (= direction :right) nil)))
```

### 1.2 Tab with Selection (Indent)
**State before:** Multiple blocks selected, not editing
**Action:** Press Tab
**What happens:** All selected blocks are indented together
**Cursor/focus after:** Not specified in code - likely stays in selection mode
**Code location:** `/src/main/frontend/handler/editor.cljs:2949-2963`

```clojure
(defn keydown-tab-handler
  [direction]
  (fn [e]
    (cond
      (state/editing?)
      (when-not (state/get-editor-action)
        (util/stop e)
        (indent-outdent (not (= :left direction))))

      (state/selection?)
      (do
        (util/stop e)
        (state/pub-event! [:editor/hide-action-bar])
        (on-tab direction)))
    nil))
```

### 1.3 Shift+Tab with Selection (Outdent)
**State before:** Blocks selected
**Action:** Press Shift+Tab
**What happens:** Calls `on-tab :left` which outdents all selected blocks
**Code location:** Same as 1.2

### 1.4 Backspace/Delete with Selection (Editing Mode)
**State before:** In edit mode with text selected (not block selection)
**Action:** Press Backspace
**What happens:** Deletes selected text range, updates input
**Cursor after:** Positioned at `selected-start`
**Code location:** `/src/main/frontend/handler/editor.cljs:2870-2876`

```clojure
(cond
  (not= selected-start selected-end)
  (do
    (util/stop e)
    (when cut?
      (js/document.execCommand "copy"))
    (delete-and-update input selected-start selected-end))
```

### 1.5 Cmd+X (Cut) with Block Selection
**State before:** Multiple blocks selected
**Action:** Cmd+X
**What happens:** Cuts blocks and clears selection
**Code location:** `/src/main/frontend/handler/editor.cljs:3249-3253`

```clojure
(defn shortcut-cut-selection
  [e]
  (when-not (util/input? (.-target e))
    (util/stop e)
    (cut-blocks-and-clear-selections! true)))
```

### 1.6 Cmd+C (Copy) with Block Selection
**State before:** Blocks selected
**Action:** Cmd+C
**What happens:** Copies blocks, then clears selection
**Code location:** `/src/main/frontend/components/selection.cljs:44-48`

```clojure
(assoc button-opts
  :on-pointer-down (fn [e]
                     (util/stop e)
                     (on-copy)
                     (state/clear-selection!)
                     (state/pub-event! [:editor/hide-action-bar])))
```

### 1.7 Delete with Block Selection
**State before:** Multiple blocks selected
**Action:** Cmd+Backspace or Delete shortcut
**What happens:** Deletes all selected blocks (cuts without copying to clipboard)
**Code location:** `/src/main/frontend/handler/editor.cljs:3255-3259`

```clojure
(defn shortcut-delete-selection
  [e]
  (when-not (util/input? (.-target e))
    (util/stop e)
    (cut-blocks-and-clear-selections! false)))
```

---

## 2. Cursor Position After Operations

### 2.1 Split Block - Enter in Middle of Text
**State before:** Editing "Hello World" with cursor at position 6 (after "Hello ")
**Action:** Press Enter
**What happens:**
- First block gets text before cursor: "Hello"
- Second block gets text after cursor (trimmed left): "World"
- New block created as sibling or child depending on context
**Cursor after:** Position 0 in new block, plus any unsaved chars
**Code location:** `/src/main/frontend/handler/editor.cljs:503-556`

```clojure
(defn insert-new-block!
  ;; ...
  (let [selection-start (util/get-selection-start input)
        selection-end (util/get-selection-end input)
        [fst-block-text snd-block-text] (compute-fst-snd-block-text value selection-start selection-end)
        ;; ...
        edit-block-f (fn []
                       (let [next-block' (db/entity [:block/uuid (:block/uuid next-block)])
                             pos 0  ;; <-- CURSOR AT START
                             unsaved-chars @(:editor/async-unsaved-chars @state/state)
                             container-id (get-new-container-id :insert {:sibling? sibling?})]
                         (edit-block! next-block' (+ pos (count unsaved-chars))
                                      {:container-id container-id
                                       :custom-content (str unsaved-chars (:block/title next-block'))})))]
```

**Key detail:** `compute-fst-snd-block-text` at line 319-324:
```clojure
(defn- compute-fst-snd-block-text
  [value selection-start selection-end]
  (when (string? value)
    (let [fst-block-text (subs value 0 selection-start)
          snd-block-text (string/triml (subs value selection-end))]  ;; <-- LEFT TRIM!
      [fst-block-text snd-block-text])))
```

### 2.2 Split Block - Enter at Start (Position 0)
**State before:** Cursor at position 0 in "Hello"
**Action:** Press Enter
**What happens:** Creates new **empty block above** current block
**Cursor after:** Position 0 in the original block (which now has "Hello")
**Code location:** `/src/main/frontend/handler/editor.cljs:521, 537-538`

```clojure
insert-above? (and (string/blank? fst-block-text) (not (string/blank? snd-block-text)))
;; ...
insert-fn (cond
            block-self?
            insert-new-block-aux!

            insert-above?
            insert-new-block-before-block-aux!  ;; <-- SPECIAL CASE
```

### 2.3 Merge Blocks - Backspace at Start
**State before:** Cursor at position 0 in block "World", previous block is "Hello"
**Action:** Press Backspace
**What happens:**
- Previous block's content: "Hello"
- Current block's content: "World"
- Result: "HelloWorld"
**Cursor after:** Position at the join point (length of "Hello" = 5)
**Code location:** `/src/main/frontend/handler/editor.cljs:772-810, 850-903`

```clojure
(defn- move-to-prev-block
  [repo sibling-block format value]
  ;; ...
  (let [new-value (str value' value)  ;; Concatenate
        tail-len (count value)
        pos (max
             (if original-content
               (gobj/get (utf8/encode original-content) "length")  ;; <-- CURSOR AT JOIN
               0)
             0)]
    {:prev-block sibling-entity
     :new-content new-value
     :pos pos  ;; <-- Position at end of previous content
     :edit-block-f #(edit-block! edit-target pos ...)}))
```

### 2.4 Delete Block - Backspace at Start with Empty Current Block
**State before:** Empty block, cursor at position 0, has previous sibling
**Action:** Backspace
**What happens:** Deletes current block, moves to previous block
**Cursor after:** Position 0 in next block if concat happens, or max position in previous block
**Code location:** `/src/main/frontend/handler/editor.cljs:2878-2890`

```clojure
(zero? current-pos)
(let [editor-state (get-state)
      custom-query? (get-in editor-state [:config :custom-query?])]
  (util/stop e)
  (when (and (not (and top-block? (not (string/blank? value))))
             (not root-block?)
             (not single-block?)
             (not custom-query?))
    (if (own-order-number-list? block)
      (p/do!
       (save-current-block!)
       (remove-block-own-order-list-type! block))
      (delete-block! repo))))
```

### 2.5 Indent/Outdent - Tab While Editing
**State before:** Editing block "Hello", cursor at position 3
**Action:** Press Tab
**What happens:** Block is indented (becomes child of previous sibling)
**Cursor after:** Stays in same block, editing continues, container-id may update
**Code location:** `/src/main/frontend/handler/editor.cljs:2937-2947`

```clojure
(defn indent-outdent
  [indent?]
  (let [{:keys [block block-container]} (get-state)]
    (when block
      (let [node block-container
            prev-container-id (get-node-container-id node)
            container-id (get-new-container-id (if indent? :indent :outdent) {})]
        (p/do!
         (block-handler/indent-outdent-blocks! [block] indent? save-current-block!)
         (when (and (not= prev-container-id container-id) container-id)
           (state/set-editing-block-id! [container-id (:block/uuid block)])))))))
```

### 2.6 Move Block Up/Down
**State before:** Editing block or block selected
**Action:** Cmd+Shift+Up or Cmd+Shift+Down
**What happens:** Block moves up/down in tree
**Cursor after:** Code saves block first, then moves it. Block scrolls into view but cursor state not explicitly restored.
**Code location:** `/src/main/frontend/handler/editor.cljs:1809-1832`

```clojure
(defn move-up-down
  [up?]
  (fn [event]
    (util/stop event)
    (state/pub-event! [:editor/hide-action-bar])
    (let [edit-block-id (:block/uuid (state/get-edit-block))
          move-nodes (fn [blocks]
                       (let [blocks' (block-handler/get-top-level-blocks blocks)
                             result (ui-outliner-tx/transact!
                                     {:outliner-op :move-blocks}
                                     (outliner-op/move-blocks-up-down! blocks' up?))]
                         (when-let [block-node (util/get-first-block-by-id (:block/uuid (first blocks)))]
                           (.scrollIntoView block-node #js {:behavior "smooth" :block "nearest"}))
                         result))]
      (if edit-block-id
        (when-let [block (db/entity [:block/uuid edit-block-id])]
          (let [blocks [(assoc block :block/title (state/get-edit-content))]
                container-id (get-new-container-id (if up? :move-up :move-down) {})]
            (p/do!
             (save-current-block!)
             (move-nodes blocks)
             ;; Note: No explicit cursor restoration here
```

### 2.7 Escape - Exit Editing
**State before:** Editing block "Hello" at any cursor position
**Action:** Press Escape
**What happens:** Saves block, selects current block (if `select?` is true)
**Cursor after:** Editing cleared, block becomes selected
**Code location:** `/src/main/frontend/handler/editor.cljs:3897-3907`

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

**Default usage in shortcut config:**
```clojure
;; /src/main/frontend/modules/shortcut/config.cljs:196-198
:editor/escape-editing {:binding []
                        :fn (fn [_ _]
                              (editor-handler/escape-editing))}
```

---

## 3. Arrow Key Edge Cases (In Edit Mode)

### 3.1 Up Arrow - Navigate to Previous Block
**State before:** Editing block, cursor at start or in first visual line
**Action:** Press Up Arrow
**What happens:** Saves current block, navigates to previous block, restores cursor column position
**Cursor after:** Same **column position** (via `get-line-pos`) in previous block
**Code location:** `/src/main/frontend/handler/editor.cljs:2639-2677`

```clojure
(let [f (if (= direction :up)
          util/get-prev-block-non-collapsed
          util/get-next-block-non-collapsed-skip)
      sibling-block (f current-block {:up-down? true})]
  ;; ...
  (edit-block! block
               (or (:pos move-opts)
                   (when input [direction (util/get-line-pos (.-value input)
                                                              (util/get-selection-start input))])
                   0)
               {:container-id container-id
                :direction direction}))
```

**Key function - `util/get-line-pos`:**
Calculates cursor position from last newline to cursor position, counting graphemes (handles emoji correctly).

**Location:** `/src/main/frontend/util.cljc:389-399`
```clojure
(defn get-line-pos
  "Return the length of the substrings in s between the last index of newline
   in s searching backward from from-newline-index and from-newline-index.

   multi-char count as 1, like emoji characters"
  [s from-newline-index]
  (let [^js splitter (GraphemeSplitter.)
        last-newline-pos (string/last-index-of s \newline (dec from-newline-index))
        before-last-newline-length (or last-newline-pos -1)
        last-newline-content (subs s (inc before-last-newline-length) from-newline-index)]
    (.countGraphemes splitter last-newline-content)))
```

### 3.2 Down Arrow - Navigate to Next Block
**State before:** Editing, cursor at end or last visual line
**Action:** Press Down Arrow
**What happens:** Same as Up but moves to next block
**Cursor after:** Maintains column position via `get-line-pos`
**Code location:** Same as 3.1

### 3.3 Up/Down Arrow - Selection Mode Extension
**State before:** One block selected
**Action:** Press Shift+Up or Shift+Down
**What happens:** Extends selection in that direction
**Code location:** `/src/main/frontend/handler/editor.cljs:1372-1379`

```clojure
;; when selection and one block selected, select next block
(and (state/selection?) (== 1 (count (state/get-selection-blocks))))
(let [f (if (= :up direction) util/get-prev-block-non-collapsed util/get-next-block-non-collapsed-skip)
      element (f (first (state/get-selection-blocks))
                 {:up-down? true
                  :exclude-property? true})]
  (when element
    (state/conj-selection-block! element direction)))
```

### 3.4 Left/Right Arrow - Text Selection Handling
**State before:** Text selected within block (not block selection)
**Action:** Press Left Arrow
**What happens:** Collapses selection, moves cursor to start
**Cursor after:** `selected-start` position
**Code location:** `/src/main/frontend/handler/editor.cljs:2773-2787`

```clojure
(not= selected-start selected-end)
(cond
  left?
  (cursor/move-cursor-to input selected-start)
  :else
  (cursor/move-cursor-to input selected-end))
```

---

## 4. Multi-Select Operations

### 4.1 Copy Multiple Blocks
**State before:** 3 blocks selected (via Shift+Up/Down)
**Action:** Cmd+C
**What happens:**
- Gets all selected block IDs
- Recursively collects blocks and their children
- Exports to EDN or plain text
- Copies to clipboard
**Selection after:** Cleared after copy
**Code location:** `/src/main/frontend/handler/editor.cljs:1043-1060`

```clojure
(defn copy-selection-blocks
  [html? & {:keys [selected-blocks] :as opts}]
  (let [repo (state/get-current-repo)
        selected-ids (state/get-selection-block-ids)
        ids (or (seq selected-ids) (map :block/uuid selected-blocks))
        ;; Get blocks with children recursively
        blocks (get-all-blocks-by-ids repo ids)
        ;; ...
        content (export-blocks repo blocks format (if (state/export-block-text-remove-options?) ...))
```

### 4.2 Delete Multiple Blocks
**State before:** 5 blocks selected
**Action:** Cmd+Backspace or Delete key
**What happens:** All selected blocks deleted, children may be re-parented
**Focus after:** Not explicitly set in selection delete handler
**Code location:** `/src/main/frontend/handler/editor.cljs:3255-3259`

### 4.3 Indent Multiple Selected Blocks
**State before:** 3 blocks selected
**Action:** Tab
**What happens:** All blocks indented together, maintains relative structure
**Code location:** `/src/main/frontend/handler/editor.cljs:1849-1853`

```clojure
(defn on-tab
  "`direction` = :left | :right."
  [direction]
  (let [blocks (get-selected-ordered-blocks)]
    (block-handler/indent-outdent-blocks! blocks (= direction :right) nil)))
```

### 4.4 Move Selected Blocks Up/Down
**State before:** Multiple blocks selected
**Action:** Cmd+Shift+Up
**What happens:** Top-level blocks from selection moved as group
**Code location:** `/src/main/frontend/handler/editor.cljs:1824-1839`

```clojure
(let [blocks (map db/entity lookup-refs)]
  (move-nodes blocks))
;; ...
(defn move-up-down [up?]
  ;; Gets top-level blocks, preserves order
  (let [blocks' (block-handler/get-top-level-blocks blocks)
        result (ui-outliner-tx/transact!
                {:outliner-op :move-blocks}
                (outliner-op/move-blocks-up-down! blocks' up?))]
```

---

## 5. Specific Cursor Position Queries

### 5.1 Enter at Block Start (Position 0)
**State before:** "Hello World", cursor at 0
**Action:** Enter
**What happens:** Creates empty block above
**Cursor after:** Position 0 in original block
**Logic:** `insert-above? (and (string/blank? fst-block-text) (not (string/blank? snd-block-text)))`
**Code location:** See 2.2

### 5.2 Shift+Enter - New Line (Not New Block)
**State before:** Editing block
**Action:** Shift+Enter
**What happens:** Inserts `\n` character at cursor position
**Cursor after:** After the newline
**Code location:** `/src/main/frontend/handler/editor.cljs:2383-2386`

```clojure
(defn- keydown-new-line
  "Insert newline to current cursor position"
  []
  (insert "\n"))
```

### 5.3 Cmd+Enter - Not Found in Editor Handler
**Action:** Cmd+Enter
**Finding:** Not found in main editor handler. Likely handled by command palette or shortcuts config.

### 5.4 Escape Twice Behavior
**First Escape:**
- Exits edit mode
- Selects current block (if `select?` param is true)

**Second Escape:**
- Would need to check selection clearing handlers
- Not explicitly documented in escape-editing function

### 5.5 Click Block While Editing Another
**State before:** Editing block A
**Action:** Click block B
**What happens:**
- Current block saved automatically (via blur/change handlers)
- New block enters edit mode
**Cursor position:** Start of clicked block (or specific position if clicked on text)
**Not explicitly shown in code** - handled by React/Replicant component click handlers

### 5.6 Autocomplete Selection
**State before:** Autocomplete menu open (page search, block search)
**Action:** Select item from autocomplete
**Cursor after:** Moves to position after inserted content
**Code location:** Various autocomplete handlers check `(state/get-editor-action)` for `:page-search`, `:block-search`, etc.

---

## 6. Delete Operation Edge Cases

### 6.1 Delete Key at End of Block
**State before:** "Hello", cursor at position 5 (end)
**Action:** Press Delete
**What happens:** Merges with next block (if exists)
**Cursor after:** Same position (5), now followed by next block's content
**Code location:** `/src/main/frontend/handler/editor.cljs:2826-2849`

```clojure
(defn keydown-delete-handler
  [_e]
  (let [^js input (state/get-input)
        current-pos (cursor/pos input)
        value (gobj/get input "value")
        end? (= current-pos (count value))  ;; <-- AT END?
        ;; ...
        (and end? current-block)
        (let [editor-state (assoc (get-state)
                                  :block-id (:block/uuid next-block)
                                  :value (:block/title next-block)
                                  ;; ...
                                  :delete-concat? true)]
          (delete-block-inner! repo editor-state)))))
```

### 6.2 Backspace with Paired Characters
**State before:** Cursor after `[`, next char is `]`
**Action:** Backspace
**What happens:** Deletes both `[` and `]` (paired deletion)
**Code location:** `/src/main/frontend/handler/editor.cljs:2900-2921`

```clojure
;; pair
(and
 deleted
 (contains? (set (keys delete-map)) deleted)
 (>= (count value) (inc current-pos))
 (= (util/nth-safe value current-pos)
    (get delete-map deleted)))

(do
  (util/stop e)
  (commands/delete-pair! id)
  (cond
    (and (= deleted "[") (state/get-editor-show-page-search?))
    (state/clear-editor-action!)
    ;; ...
```

**Delete map includes:** `{ "[" "]", "(" ")", "{" "}", ...}`

---

## 7. Additional Findings

### 7.1 Selection Direction Tracking
Logseq tracks **selection direction** (`:up` or `:down`) when extending selection with Shift+Arrow keys.

**Code location:** `/src/main/frontend/handler/editor.cljs:1382-1393`

```clojure
;; if same direction, keep conj on same direction
(and (state/selection?) (= direction (state/get-selection-direction)))
(let [f (if (= :up direction) util/get-prev-block-non-collapsed util/get-next-block-non-collapsed-skip)
      first-last (if (= :up direction) first last)
      element (f (first-last (state/get-selection-blocks))
                 {:up-down? true
                  :exclude-property? true})]
  (when element
    (state/conj-selection-block! element direction)))
```

### 7.2 Grapheme-Aware Cursor Positioning
Logseq uses `GraphemeSplitter` library to handle multi-byte characters (emoji, etc.) correctly when calculating cursor positions.

**Code location:** `/src/main/frontend/util.cljc:380-399`

### 7.3 Container ID Tracking
Blocks track a `container-id` which changes during operations like indent/outdent/move. This is used for:
- Embedded blocks
- Reference containers
- Moving focus across containers

**Code location:** `/src/main/frontend/handler/editor.cljs:458-501`

### 7.4 Async Unsaved Characters
When typing fast, Logseq buffers unsaved characters in `:editor/async-unsaved-chars` and prepends them when creating new blocks.

**Code location:** `/src/main/frontend/handler/editor.cljs:546-549`

```clojure
(let [next-block' (db/entity [:block/uuid (:block/uuid next-block)])
      pos 0
      unsaved-chars @(:editor/async-unsaved-chars @state/state)
      container-id (get-new-container-id :insert {:sibling? sibling?})]
  (edit-block! next-block' (+ pos (count unsaved-chars))
               {:container-id container-id
                :custom-content (str unsaved-chars (:block/title next-block'))}))
```

---

## Summary of Key Patterns

### Cursor Position After Block Split (Enter):
- **At start (pos 0):** New empty block created **above**, cursor stays at 0 in original block
- **In middle:** First block gets left part, second gets right part (left-trimmed), cursor at 0 in new block
- **At end:** New empty block created below, cursor at 0

### Cursor Position After Block Merge (Backspace at start):
- Cursor positioned at **join point** (length of previous block's content)
- Uses UTF-8 byte length for accurate positioning

### Cursor Position After Navigation (Arrow Keys):
- **Up/Down:** Maintains **column position** using `get-line-pos` (grapheme-aware)
- **Left/Right with selection:** Collapses to start or end of selection

### Selection Operations:
- **Copy:** Clears selection after copy
- **Cut/Delete:** Clears selection, removes blocks
- **Tab/Shift+Tab:** Indents/outdents all selected blocks together
- **Move Up/Down:** Moves top-level blocks as group

### Special Behaviors:
- **Paired character deletion:** Backspace deletes both `[` and `]` if adjacent
- **Delete at end:** Merges with next block
- **Escape:** Exits editing, optionally selects current block
- **Shift+Enter:** Inserts newline within block (doesn't create new block)

---

## Files Analyzed

1. `/src/main/frontend/handler/editor.cljs` (172KB, main editor logic)
2. `/src/main/frontend/handler/block.cljs` (block operations)
3. `/src/main/frontend/components/selection.cljs` (selection UI)
4. `/src/main/frontend/util.cljc` (cursor position utilities)
5. `/src/main/frontend/modules/shortcut/config.cljs` (keyboard shortcuts)

---

**Research Date:** 2025-11-13
**Codebase:** Logseq @ `~/Projects/best/logseq`
**Total Edge Cases Documented:** 25+
