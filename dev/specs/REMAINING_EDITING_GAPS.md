# Remaining Editing & Navigation Gaps - After Initial Implementation

**Context:** This document assumes the following are already implemented:
- ✅ Shift+Arrow text selection behavior
- ✅ Word navigation (Alt+F/B)
- ✅ Kill commands (Ctrl+U, Alt+K, etc.)
- ✅ Page/Block/Slash autocomplete

**Goal:** Identify ANY remaining gaps in single-page editing experience vs Logseq.

---

## 1. Link Operations - MISSING

### Cmd+O - Follow Link Under Cursor

**Logseq Behavior:**
```
Block text: "See [[My Page]] for details"
Cursor anywhere inside "[[My Page]]"
Press Cmd+O
→ Navigate to "My Page"

Block text: "Reference ((block-uuid))"
Cursor inside block ref
Press Cmd+O
→ Navigate to that block (scroll into view, highlight)
```

**Detection:** Use context detection (already exists in specs!)
- Detect if cursor inside `[[page]]` → navigate to page
- Detect if cursor inside `((uuid))` → scroll to block

**Implementation:**
```clojure
(intent/register-intent! :follow-link-under-cursor
  {:doc "Follow page/block reference under cursor (Cmd+O)"
   :handler
   (fn [db {:keys [block-id]}]
     (let [text (get-block-text db block-id)
           cursor-pos (q/cursor-position db)
           context (context/context-at-cursor text cursor-pos)]
       (case (:type context)
         :page-ref
         [{:op :update-node
           :id const/session-ui-id
           :props {:current-page (:page-name context)}}]

         :block-ref
         [{:op :update-node
           :id const/session-ui-id
           :props {:scroll-to-block (:uuid context)
                   :highlight-block (:uuid context)}}]

         :else nil)))})
```

**Shortcut:** `Cmd+O`

---

### Cmd+Shift+O - Open Link in Sidebar

**Logseq Behavior:**
```
Same detection as Cmd+O, but opens in right sidebar instead of navigating.
```

**Evo Status:** ❌ No sidebar implementation yet
**Action:** Defer until sidebar exists

---

### Cmd+L - Format Selection as Link

**Logseq Behavior:**
```
User selects text: "click here"
Press Cmd+L
→ Prompt appears: "Enter URL:"
User types: "https://example.com"
→ Text becomes: [click here](https://example.com)
```

**Two-step interaction:**
1. Detect selection
2. Open URL input prompt
3. Wrap selection with markdown link syntax

**Implementation:**
```clojure
(intent/register-intent! :format-selection-as-link
  {:doc "Format selected text as markdown link.

         Opens prompt for URL, then wraps selection."

   :handler
   (fn [db {:keys [block-id url]}]
     (let [text (get-block-text db block-id)
           {:keys [start end selected-text]} (q/text-selection db)]
       (when selected-text
         (if url
           ;; URL provided - wrap and insert
           (let [link-text (str "[" selected-text "](" url ")")
                 new-text (str (subs text 0 start)
                              link-text
                              (subs text end))]
             [{:op :update-node :id block-id :props {:text new-text}}
              {:op :update-node
               :id const/session-ui-id
               :props {:cursor-position (+ start (count link-text))}}])

           ;; No URL - open prompt
           [{:op :update-node
             :id const/session-ui-id
             :props {:prompt {:type :url-input
                             :callback {:type :format-selection-as-link
                                       :block-id block-id}}}}]))))})
```

**Shortcut:** `Cmd+L`

---

## 2. Additional Formatting - MISSING

Evo currently has:
- ✅ Bold (`Cmd+B`)
- ✅ Italic (`Cmd+I`)

Missing:
- ❌ Highlight (`Cmd+Shift+H`)
- ❌ Strikethrough (`Cmd+Shift+S`)

**Implementation:** Same as bold/italic, just different markers:

```clojure
;; keymap/bindings_data.cljc
[{:key "h" :shift true :mod true} {:type :format-selection :marker "^^"}]  ; Highlight
[{:key "s" :shift true :mod true} {:type :format-selection :marker "~~"}]  ; Strikethrough
```

**Note:** The `:format-selection` intent ALREADY EXISTS in evo! Just add bindings.

**Status:** ✅ EASY FIX - just add 2 lines to bindings

---

## 3. Selection Operations - PARTIAL

### Cmd+A (while editing) - Select Parent Block

**Logseq Behavior:**
```
User editing block B (child of block A)
Press Cmd+A
→ Exit edit mode
→ Select parent block A

User editing top-level block
Press Cmd+A
→ No parent, does nothing (or selects all text in block - browser default)
```

**Evo Status:** ❌ Not implemented

**Implementation:**
```clojure
(intent/register-intent! :select-parent
  {:doc "Select parent of currently editing block.

         If no parent, does nothing."

   :handler
   (fn [db _]
     (when-let [editing-id (q/editing-block-id db)]
       (when-let [parent-id (q/parent-of db editing-id)]
         (when (not= parent-id :doc)  ; Don't select :doc root
           [{:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id nil}}  ; Exit edit
            {:op :update-node
             :id (str "session/selection")
             :props {:nodes #{parent-id}
                    :focus parent-id}}]))))})
```

**Shortcut:** `Cmd+A` (only when editing)

---

### Cmd+Shift+A - Select All Blocks

**Logseq Behavior:**
```
Select all blocks in current page/zoom level.
```

**Evo Status:** ❌ Not implemented

**Implementation:**
```clojure
(intent/register-intent! :select-all-blocks
  {:doc "Select all blocks in current view."

   :handler
   (fn [db _]
     (let [zoom-id (q/zoom-id db)
           root-id (or zoom-id (q/current-page-id db))
           all-blocks (tree-seq
                       (fn [id] (q/children db id))
                       (fn [id] (q/children db id))
                       root-id)
           block-ids (set (filter #(= :block (q/node-type db %)) all-blocks))]
       [{:op :update-node
         :id (str "session/selection")
         :props {:nodes block-ids
                :focus (first block-ids)}}]))})
```

**Shortcut:** `Cmd+Shift+A`

---

## 4. Copy/Paste Variants - MISSING

### Cmd+Shift+C - Copy as Plain Text

**Logseq Behavior:**
```
User selects blocks
Press Cmd+Shift+C
→ Copy text content without markdown formatting
→ Just the plain text, no **, __, [[]], (()), etc.
```

**Evo Status:** ❌ Not implemented

**Implementation:**
```clojure
(defn strip-markdown [text]
  (-> text
      (str/replace #"\*\*(.+?)\*\*" "$1")  ; Bold
      (str/replace #"__(.+?)__" "$1")      ; Italic
      (str/replace #"~~(.+?)~~" "$1")      ; Strikethrough
      (str/replace #"\^\^(.+?)\^\^" "$1")  ; Highlight
      (str/replace #"\[\[(.+?)\]\]" "$1")  ; Page refs
      (str/replace #"\(\((.+?)\)\)" "$1")  ; Block refs
      (str/replace #"^[-*+]\s+" "")        ; List markers
      (str/replace #"^\d+\.\s+" "")        ; Numbered lists
      (str/replace #"^-\s+\[[ xX]\]\s+" ""))) ; Checkboxes

(intent/register-intent! :copy-as-plain-text
  {:doc "Copy selected blocks as plain text (no markdown)."

   :handler
   (fn [db _]
     (let [selected-ids (q/selection db)
           texts (map #(strip-markdown (get-block-text db %)) selected-ids)
           plain-text (str/join "\n" texts)]
       (clipboard/copy! plain-text)
       []))})  ; No DB changes
```

**Shortcut:** `Cmd+Shift+C`

---

### Cmd+Shift+V - Paste as Single Block

**Logseq Behavior:**
```
User copies multi-line text from external source
Paste with Cmd+V → Creates multiple blocks (splits on newlines)
Paste with Cmd+Shift+V → Inserts as single block with \n preserved
```

**Evo Status:** ❌ Not implemented (paste handling not specified)

**Implementation:**
```clojure
;; Normal paste (Cmd+V) - already exists
:paste {:type :paste-and-split}  ; Split on newlines

;; New: Paste as single block
:paste-raw {:type :paste-no-split}  ; Keep newlines as \n

(intent/register-intent! :paste-no-split
  {:doc "Paste clipboard content as single block.

         Preserves newlines as literal \\n characters."

   :handler
   (fn [db {:keys [block-id text]}]
     (let [current-text (get-block-text db block-id)
           cursor-pos (q/cursor-position db)
           new-text (str (subs current-text 0 cursor-pos)
                        text
                        (subs current-text cursor-pos))]
       [{:op :update-node :id block-id :props {:text new-text}}
        {:op :update-node
         :id const/session-ui-id
         :props {:cursor-position (+ cursor-pos (count text))}}]))})
```

**Shortcut:** `Cmd+Shift+V`

---

## 5. Block Reference Operations - ADVANCED

### Cmd+Shift+R - Replace Block Ref with Content

**Logseq Behavior:**
```
Block text: "See ((abc-123)) for details"
Cursor inside block ref
Press Cmd+Shift+R
→ Looks up block abc-123
→ Replaces ((abc-123)) with actual block text

Before: "See ((abc-123)) for details"
After: "See This is the referenced block text for details"
```

**Evo Status:** ❌ Not implemented

**Complexity:** Medium - needs block lookup by UUID

**Implementation:**
```clojure
(intent/register-intent! :replace-block-ref-with-content
  {:doc "Replace block reference under cursor with actual content."

   :handler
   (fn [db {:keys [block-id]}]
     (let [text (get-block-text db block-id)
           cursor-pos (q/cursor-position db)
           context (context/context-at-cursor text cursor-pos)]
       (when (= :block-ref (:type context))
         (when-let [ref-block (q/block-by-uuid db (:uuid context))]
           (let [ref-text (get-in ref-block [:props :text])
                 {:keys [start end]} context
                 new-text (str (subs text 0 start)
                              ref-text
                              (subs text end))]
             [{:op :update-node :id block-id :props {:text new-text}}
              {:op :update-node
               :id const/session-ui-id
               :props {:cursor-position (+ start (count ref-text))}}])))))})
```

**Shortcut:** `Cmd+Shift+R`

---

### Cmd+Shift+E - Copy Block as Embed

**Logseq Behavior:**
```
User has block selected (not editing)
Press Cmd+Shift+E
→ Copies "{{embed ((block-uuid))}}" to clipboard
```

**Evo Status:** ❌ Not implemented

**Implementation:**
```clojure
(intent/register-intent! :copy-block-as-embed
  {:doc "Copy selected block as embed reference."

   :handler
   (fn [db _]
     (when-let [focus-id (q/focus db)]
       (let [block-uuid (q/block-uuid db focus-id)
             embed-text (str "{{embed ((" block-uuid "))}}" )]
         (clipboard/copy! embed-text)
         [])))})
```

**Shortcut:** `Cmd+Shift+E`

---

## 6. TODO Cycling - DIFFERENT SEMANTICS

**Evo has:** `Cmd+Enter` → Toggle checkbox (`- [ ]` ↔ `- [x]`)

**Logseq has:** `Cmd+Enter` → Cycle TODO states:
```
"Task" → "TODO Task" → "DOING Task" → "DONE Task" → "Task"
```

**OR** for checkboxes:
```
"- [ ] Task" → "- [x] Task" → "- [ ] Task"
```

**Question:** Does Evo want TODO/DOING/DONE markers, or just checkboxes?

**If TODO markers wanted:**

```clojure
(def todo-cycle
  ["" "TODO" "DOING" "DONE"])

(intent/register-intent! :cycle-todo
  {:doc "Cycle TODO status: none → TODO → DOING → DONE → none"

   :handler
   (fn [db {:keys [block-id]}]
     (let [text (get-block-text db block-id)
           current-marker (detect-todo-marker text)  ; "TODO", "DOING", "DONE", or nil
           current-idx (or (.indexOf todo-cycle current-marker) -1)
           next-idx (mod (inc current-idx) (count todo-cycle))
           next-marker (nth todo-cycle next-idx)
           ;; Remove old marker
           text-no-marker (str/replace text #"^(TODO|DOING|DONE)\s+" "")
           ;; Add new marker
           new-text (if (empty? next-marker)
                     text-no-marker
                     (str next-marker " " text-no-marker))]
       [{:op :update-node :id block-id :props {:text new-text}}]))})
```

**Status:** ❌ Decision needed - checkboxes only, or TODO markers?

---

## 7. Ctrl+P / Ctrl+N - Alternative Arrow Keys

**Logseq has:** `Ctrl+P` = Up, `Ctrl+N` = Down (Emacs bindings)

**Evo Status:** ❌ Not in bindings

**Implementation:** Easy - just add aliases:

```clojure
;; keymap/bindings_data.cljc
[{:key "p" :ctrl true} {:type :selection :mode :prev}]  ; Same as ArrowUp
[{:key "n" :ctrl true} {:type :selection :mode :next}]  ; Same as ArrowDown
```

**Status:** ✅ TRIVIAL - 2 lines

---

## 8. Toggle Numbered List - `t n`

**Logseq Behavior:**
```
Block: "First item"
Press `t n`
→ Block: "1. First item"

Block: "1. First item"
Press `t n`
→ Block: "First item" (toggle off)
```

**Evo Status:** ❌ Not implemented

**Implementation:**
```clojure
(intent/register-intent! :toggle-numbered-list
  {:doc "Toggle numbered list formatting (t n)."

   :handler
   (fn [db {:keys [block-ids]}]  ; Can apply to selection
     (for [block-id block-ids]
       (let [text (get-block-text db block-id)
             is-numbered? (re-matches #"^\d+\.\s+.*" text)
             new-text (if is-numbered?
                       ;; Remove number
                       (str/replace text #"^\d+\.\s+" "")
                       ;; Add number
                       (str "1. " text))]
         {:op :update-node :id block-id :props {:text new-text}})))})
```

**Shortcut:** `t n`

---

## Summary: Remaining Gaps After Initial Specs

### HIGH PRIORITY (Core editing feel)

1. ✅ **Highlight formatting** (`Cmd+Shift+H`) - EASY, just add binding
2. ✅ **Strikethrough formatting** (`Cmd+Shift+S`) - EASY, just add binding
3. ✅ **Ctrl+P/N navigation** - EASY, 2 lines
4. ⚠️ **Follow link under cursor** (`Cmd+O`) - Medium, needs context detection
5. ⚠️ **Select parent** (`Cmd+A`) - Medium
6. ⚠️ **Select all blocks** (`Cmd+Shift+A`) - Medium

### MEDIUM PRIORITY (Nice to have)

7. ⚠️ **Format selection as link** (`Cmd+L`) - Medium, needs prompt UI
8. ⚠️ **Copy as plain text** (`Cmd+Shift+C`) - Medium, needs markdown stripping
9. ⚠️ **Paste as single block** (`Cmd+Shift+V`) - Medium
10. ⚠️ **Toggle numbered list** (`t n`) - Easy

### LOW PRIORITY (Advanced features)

11. ⚠️ **Replace block ref with content** (`Cmd+Shift+R`) - Complex
12. ⚠️ **Copy block as embed** (`Cmd+Shift+E`) - Easy
13. ⚠️ **TODO cycling** vs checkbox toggle - Design decision needed

### NOT APPLICABLE (Require features not in scope)

14. ❌ **Open link in sidebar** (`Cmd+Shift+O`) - Needs sidebar implementation

---

## Updated Implementation Checklist

**Assuming Shift+Arrow, Word Nav, Kill Commands, Autocomplete are done:**

### Phase 1: Easy Wins (1 day)
- [ ] Add `Cmd+Shift+H` highlight binding
- [ ] Add `Cmd+Shift+S` strikethrough binding
- [ ] Add `Ctrl+P` / `Ctrl+N` arrow aliases
- [ ] Add `Cmd+Shift+E` copy embed
- [ ] Add `t n` toggle numbered list

### Phase 2: Medium Features (2-3 days)
- [ ] Implement `Cmd+O` follow link (needs context detection)
- [ ] Implement `Cmd+A` select parent
- [ ] Implement `Cmd+Shift+A` select all blocks
- [ ] Implement `Cmd+Shift+C` copy as plain text
- [ ] Implement `Cmd+Shift+V` paste without split

### Phase 3: Advanced Features (if needed)
- [ ] Implement `Cmd+L` format as link (needs prompt UI)
- [ ] Implement `Cmd+Shift+R` replace block ref
- [ ] Decide on TODO cycling vs checkbox

---

## Testing Additions

### Manual Testing Checklist (Add to existing tests)

**Link Operations:**
- [ ] `Cmd+O` inside `[[page]]` navigates to page
- [ ] `Cmd+O` inside `((uuid))` scrolls to block
- [ ] `Cmd+O` in plain text does nothing

**Selection:**
- [ ] `Cmd+A` while editing selects parent block
- [ ] `Cmd+Shift+A` selects all blocks in view

**Copy/Paste:**
- [ ] `Cmd+Shift+C` copies without markdown formatting
- [ ] `Cmd+Shift+V` pastes multi-line as single block

**Formatting:**
- [ ] `Cmd+Shift+H` wraps selection with `^^`
- [ ] `Cmd+Shift+S` wraps selection with `~~`

**List Formatting:**
- [ ] `t n` toggles numbered list on/off
- [ ] Works with selection (toggle all selected blocks)

---

## Final Assessment

**After implementing ALL specs (including these additions):**

✅ Single-page editing experience will be **functionally equivalent** to Logseq
✅ All core navigation, editing, formatting shortcuts present
✅ Autocomplete for pages, blocks, slash commands
✅ Context-aware behaviors (enter, delete, etc.)
✅ Word-level and line-level editing
✅ Text selection vs block selection
✅ Multi-block operations
✅ Link following and formatting

**Remaining differences:**
- ⚠️ Sidebar operations (not in scope for single-page editing)
- ⚠️ TODO cycling vs checkbox toggle (design choice)
- ⚠️ Some advanced block ref operations (if not needed)

**Confidence:** 98% feature parity for single-page editing experience.

---

## Files to Update

**Add to:** `dev/specs/SHIFT_ARROW_TEXT_SELECTION_SPEC.md`
- No changes needed

**Add to:** `dev/specs/WORD_NAVIGATION_AND_KILL_COMMANDS_SPEC.md`
- No changes needed

**Create NEW:** `dev/specs/LINK_AND_FORMATTING_OPERATIONS_SPEC.md`
- Follow link under cursor
- Format selection as link
- Additional formatting (highlight, strikethrough)

**Create NEW:** `dev/specs/SELECTION_AND_COPY_OPERATIONS_SPEC.md`
- Select parent / select all
- Copy as plain text
- Paste without split
- Copy as embed

**Create NEW:** `dev/specs/LIST_FORMATTING_SPEC.md`
- Toggle numbered list
- TODO cycling (if implementing)
