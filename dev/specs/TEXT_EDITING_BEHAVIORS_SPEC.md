# Text Editing Behaviors Specification

**Goal:** Match Logseq's exact text editing feel through context-aware operations, paired character handling, and precise cursor positioning.

**Context:** This spec covers **within-block editing behaviors** - character insertion, deletion, navigation, and text selection. Complements `LOGSEQ_FEEL_IMPLEMENTATION_SPEC.md` which covers block-level navigation.

---

## Architecture Overview

### Where Behaviors Live

| Behavior | Implementation Location | Notes |
|----------|------------------------|-------|
| Context detection | `src/plugins/context.cljc` | NEW PLUGIN - detect markup, refs, etc. |
| Paired characters | `src/plugins/smart_editing.cljc` | ENHANCE - add pair insertion/deletion |
| Enter key routing | `src/plugins/smart_editing.cljc` | ENHANCE - context-aware split |
| Delete key | `src/plugins/editing.cljc` | ENHANCE - add merge-with-next |
| Arrow boundaries | `src/components/block.cljs` | ENHANCE - add left/right handlers |
| Selection collapse | `src/components/block.cljs` | ENHANCE - collapse before navigate |
| Trigger cleanup | `src/plugins/autocomplete.cljc` | NEW PLUGIN - manage trigger state |

---

## Component 1: Context Detection (NEW PLUGIN)

**File:** `src/plugins/context.cljc`

### Purpose
Detect what the cursor is inside (markup, refs, code blocks, etc.) to enable context-aware editing behaviors.

### Data Structures

```clojure
;; Context types
(s/def ::context-type
  #{:markup           ; **bold**, __italic__, ~~strike~~
    :code-block       ; ```lang\n...\n```
    :admonition       ; #+BEGIN_NOTE ... #+END
    :block-ref        ; ((uuid))
    :page-ref         ; [[page name]]
    :property-drawer  ; :PROPERTIES:\n:key: value\n:END:
    :list-item        ; - item, * item, 1. item
    :checkbox         ; - [ ] task, - [x] done
    :none})           ; Plain text

;; Context result
{:type :markup
 :marker "**"              ; What markup (**, __, ~~, etc.)
 :start 5                  ; Start position in text
 :end 15                   ; End position
 :inner-start 7            ; Start of content (after opening marker)
 :inner-end 13             ; End of content (before closing marker)
 :complete? true}          ; Both markers present?
```

### Core Functions

#### 1. Detect Context at Cursor

```clojure
(defn context-at-cursor
  "Detect what context the cursor is within.

   Returns context map or {:type :none} if in plain text.

   Args:
     text: Block text content
     cursor-pos: Integer cursor position

   Returns:
     {:type :markup :marker \"**\" :start 5 :end 15 ...}
     or {:type :none}"
  [text cursor-pos]
  (or (detect-markup-at-cursor text cursor-pos)
      (detect-code-block-at-cursor text cursor-pos)
      (detect-block-ref-at-cursor text cursor-pos)
      (detect-page-ref-at-cursor text cursor-pos)
      (detect-list-item-at-cursor text cursor-pos)
      {:type :none}))
```

#### 2. Detect Markup (Bold, Italic, Strike, Highlight)

```clojure
(defn detect-markup-at-cursor
  "Detect if cursor is inside markup like **bold** or __italic__.

   Handles:
   - **text** (bold)
   - __text__ (italic)
   - ~~text~~ (strikethrough)
   - ^^text^^ (highlight)

   Returns nil if not in markup."
  [text cursor-pos]
  (let [patterns [{:marker "**" :type :bold}
                  {:marker "__" :type :italic}
                  {:marker "~~" :type :strike}
                  {:marker "^^" :type :highlight}]]
    ;; For each pattern, scan backwards/forwards from cursor
    ;; Find matching pairs where cursor is between them
    (some (fn [{:keys [marker type]}]
            (when-let [bounds (find-enclosing-pair text cursor-pos marker)]
              (assoc bounds :type :markup :marker marker :markup-type type)))
          patterns)))

(defn find-enclosing-pair
  "Find enclosing pair of markers around cursor position.

   Example:
     text: 'hello **world** test'
     cursor-pos: 10 (inside 'world')
     marker: '**'
     => {:start 6 :end 15 :inner-start 8 :inner-end 13 :complete? true}

   Returns nil if not found or cursor not inside pair."
  [text cursor-pos marker]
  (let [marker-len (count marker)
        ;; Search backwards for opening marker
        opening (find-previous-marker text cursor-pos marker)
        ;; Search forwards for closing marker
        closing (find-next-marker text cursor-pos marker)]
    (when (and opening closing
               (< (:pos opening) cursor-pos (:pos closing)))
      {:start (:pos opening)
       :end (+ (:pos closing) marker-len)
       :inner-start (+ (:pos opening) marker-len)
       :inner-end (:pos closing)
       :complete? true})))
```

#### 3. Detect Block/Page References

```clojure
(defn detect-block-ref-at-cursor
  "Detect if cursor is inside ((block-ref)).

   Example:
     text: 'See ((abc-123-def)) for details'
     cursor-pos: 10 (inside uuid)
     => {:type :block-ref :start 4 :end 18 :uuid 'abc-123-def'}"
  [text cursor-pos]
  (when-let [bounds (find-enclosing-pair text cursor-pos "((")]
    (let [inner-text (subs text (:inner-start bounds) (:inner-end bounds))
          uuid? (re-matches #"[a-f0-9-]+" inner-text)]
      (when uuid?
        (assoc bounds
               :type :block-ref
               :uuid inner-text)))))

(defn detect-page-ref-at-cursor
  "Detect if cursor is inside [[page-ref]].

   Example:
     text: 'Link to [[My Page]] here'
     cursor-pos: 12 (inside 'My Page')
     => {:type :page-ref :start 8 :end 19 :page-name 'My Page'}"
  [text cursor-pos]
  (when-let [bounds (find-enclosing-pair text cursor-pos "[[")]
    (let [inner-text (subs text (:inner-start bounds) (:inner-end bounds))]
      (assoc bounds
             :type :page-ref
             :page-name inner-text))))
```

#### 4. Detect Code Blocks

```clojure
(defn detect-code-block-at-cursor
  "Detect if cursor is inside ```code block```.

   Example:
     text: '```clojure\\n(+ 1 2)\\n```'
     cursor-pos: 15 (inside code)
     => {:type :code-block :lang 'clojure' :start 0 :end 24}"
  [text cursor-pos]
  (let [lines (string/split text #"\n")
        ;; Find line with cursor
        cursor-line (find-line-at-pos lines cursor-pos)
        ;; Search backwards for ```
        open-marker (find-code-block-start lines cursor-line)
        ;; Search forwards for ```
        close-marker (find-code-block-end lines cursor-line)]
    (when (and open-marker close-marker)
      {:type :code-block
       :lang (:lang open-marker)
       :start (:pos open-marker)
       :end (:pos close-marker)
       :inner-start (:inner-start open-marker)
       :inner-end (:inner-end close-marker)})))
```

#### 5. Detect List Items

```clojure
(defn detect-list-item-at-cursor
  "Detect if cursor is on a line starting with list marker.

   Handles:
   - - item (dash)
   - * item (asterisk)
   - + item (plus)
   - 1. item (numbered)
   - [ ] task (checkbox unchecked)
   - [x] task (checkbox checked)

   Returns:
     {:type :list-item :marker '- ' :start 0 :checkbox? false}
     or {:type :checkbox :marker '- [ ]' :checked? false}"
  [text cursor-pos]
  (let [line-start (find-line-start text cursor-pos)
        line-text (subs text line-start (find-line-end text cursor-pos))
        ;; Check for checkbox first (more specific)
        checkbox-match (re-matches #"^([-*+])\s+(\[[ xX]\])\s+(.*)$" line-text)
        ;; Then check for numbered list
        numbered-match (re-matches #"^(\d+)\.\s+(.*)$" line-text)
        ;; Finally check for simple list
        list-match (re-matches #"^([-*+])\s+(.*)$" line-text)]
    (cond
      checkbox-match
      (let [[_ bullet checkbox content] checkbox-match
            checked? (not= (string/trim checkbox) "[ ]")]
        {:type :checkbox
         :marker (str bullet " " checkbox)
         :checked? checked?
         :start line-start
         :content content})

      numbered-match
      (let [[_ number content] numbered-match]
        {:type :list-item
         :marker (str number ". ")
         :numbered? true
         :number (parse-long number)
         :start line-start
         :content content})

      list-match
      (let [[_ bullet content] list-match]
        {:type :list-item
         :marker (str bullet " ")
         :numbered? false
         :start line-start
         :content content})

      :else nil)))
```

---

## Component 2: Paired Character Handling (ENHANCE EXISTING)

**File:** `src/plugins/smart_editing.cljc`

### Auto-Closing Pairs

```clojure
(def pairs
  "Character pairs that auto-close and delete together."
  {"[" "]"
   "(" ")"
   "{" "}"
   "\"" "\""
   "**" "**"    ; Bold
   "__" "__"    ; Italic
   "~~" "~~"    ; Strikethrough
   "^^" "^^"})  ; Highlight

(intent/register-intent! :insert-paired-char
  {:doc "Insert character with auto-closing pair.

         If opening char typed, insert both and position cursor between.
         If closing char typed and next char matches, skip over it instead."

   :spec [:map
          [:type [:= :insert-paired-char]]
          [:block-id :string]
          [:cursor-pos :int]
          [:char :string]]

   :handler
   (fn [db {:keys [block-id cursor-pos char]}]
     (let [text (get-block-text db block-id)
           next-char (when (< cursor-pos (count text))
                      (str (nth text cursor-pos)))
           closing-char (get pairs char)]

       (cond
         ;; Closing char and next char matches - skip over
         (and (contains? (set (vals pairs)) char)
              (= next-char char))
         [{:op :update-node
           :id const/session-ui-id
           :props {:editing-block-id block-id
                   :cursor-position (inc cursor-pos)}}]

         ;; Opening char - insert both and position cursor between
         closing-char
         (let [new-text (str (subs text 0 cursor-pos)
                            char
                            closing-char
                            (subs text cursor-pos))]
           [{:op :update-node
             :id block-id
             :props {:text new-text}}
            {:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id block-id
                     :cursor-position (+ cursor-pos (count char))}}])

         ;; Not a paired char - just insert
         :else
         (let [new-text (str (subs text 0 cursor-pos)
                            char
                            (subs text cursor-pos))]
           [{:op :update-node
             :id block-id
             :props {:text new-text}}
            {:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id block-id
                     :cursor-position (+ cursor-pos (count char))}}]))))})
```

### Paired Deletion

```clojure
(intent/register-intent! :delete-with-pair-check
  {:doc "Delete character, removing paired closing char if present.

         If cursor is after opening char and before closing char,
         delete both (e.g., [|] becomes empty).

         Otherwise, normal backspace behavior."

   :spec [:map
          [:type [:= :delete-with-pair-check]]
          [:block-id :string]
          [:cursor-pos :int]]

   :handler
   (fn [db {:keys [block-id cursor-pos]}]
     (when (pos? cursor-pos)
       (let [text (get-block-text db block-id)
             prev-char (str (nth text (dec cursor-pos)))
             next-char (when (< cursor-pos (count text))
                        (str (nth text cursor-pos)))
             expected-closing (get pairs prev-char)]

         (if (and expected-closing (= next-char expected-closing))
           ;; Delete both pair characters
           (let [new-text (str (subs text 0 (dec cursor-pos))
                              (subs text (inc cursor-pos)))]
             [{:op :update-node
               :id block-id
               :props {:text new-text}}
              {:op :update-node
               :id const/session-ui-id
               :props {:editing-block-id block-id
                       :cursor-position (dec cursor-pos)}}])

           ;; Normal backspace - delete one char
           (let [new-text (str (subs text 0 (dec cursor-pos))
                              (subs text cursor-pos))]
             [{:op :update-node
               :id block-id
               :props {:text new-text}}
              {:op :update-node
               :id const/session-ui-id
               :props {:editing-block-id block-id
                       :cursor-position (dec cursor-pos)}}])))))})
```

---

## Component 3: Context-Aware Enter (ENHANCE EXISTING)

**File:** `src/plugins/smart_editing.cljc`

### Enhanced Smart Split

```clojure
(intent/register-intent! :context-aware-enter
  {:doc "Handle Enter key with context awareness.

         Behaviors by context:
         - Inside markup (**, __, etc.) → Exit markup first, then new block
         - Inside code block → Insert newline (stay in block)
         - Inside block-ref ((ref)) → Open ref in sidebar (no split)
         - Inside page-ref [[page]] → Navigate to page (no split)
         - Empty list item → Unformat (remove marker)
         - List item with content → Continue list pattern
         - Checkbox → Continue checkbox pattern
         - Plain text → Split into two blocks"

   :spec [:map
          [:type [:= :context-aware-enter]]
          [:block-id :string]
          [:cursor-pos :int]]

   :handler
   (fn [db {:keys [block-id cursor-pos]}]
     (let [text (get-block-text db block-id)
           context (context/context-at-cursor text cursor-pos)]

       (case (:type context)

         ;; Inside markup - exit markup first
         :markup
         (let [exit-pos (:end context)  ; Move cursor after closing marker
               new-cursor-pos exit-pos]
           [{:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id block-id
                     :cursor-position new-cursor-pos}}
            ;; Caller should handle Enter again after cursor moves
            ])

         ;; Inside code block - insert newline (don't create new block)
         :code-block
         (let [new-text (str (subs text 0 cursor-pos)
                            "\n"
                            (subs text cursor-pos))]
           [{:op :update-node
             :id block-id
             :props {:text new-text}}
            {:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id block-id
                     :cursor-position (inc cursor-pos)}}])

         ;; Inside block-ref - open in sidebar
         :block-ref
         [{:op :update-node
           :id const/session-ui-id
           :props {:sidebar-opened-ref (:uuid context)}}]

         ;; Inside page-ref - navigate to page
         :page-ref
         [{:op :update-node
           :id const/session-ui-id
           :props {:navigate-to-page (:page-name context)}}]

         ;; Checkbox - check context for empty
         :checkbox
         (if (string/blank? (:content context))
           ;; Empty checkbox - unformat
           [{:op :update-node
             :id block-id
             :props {:text ""}}]
           ;; Checkbox with content - continue pattern
           (split-and-continue-checkbox db block-id cursor-pos context))

         ;; List item - check for empty
         :list-item
         (if (string/blank? (:content context))
           ;; Empty list - unformat (unless numbered parent exists)
           (if (:numbered? context)
             ;; Numbered list - remove number
             [{:op :update-node
               :id block-id
               :props {:text ""}}]
             ;; Regular list - unformat
             [{:op :update-node
               :id block-id
               :props {:text ""}}])
           ;; List with content - continue pattern
           (if (:numbered? context)
             (split-and-increment-number db block-id cursor-pos context)
             (split-and-continue-list db block-id cursor-pos context)))

         ;; Plain text - normal split
         :none
         (split-block-normal db block-id cursor-pos))))})

(defn split-and-continue-checkbox
  "Split block and continue checkbox pattern in new block."
  [db block-id cursor-pos context]
  (let [text (get-block-text db block-id)
        marker-len (count (:marker context))
        ;; Split point is relative to line start
        line-start (:start context)
        split-point (+ line-start marker-len)
        before (subs text 0 split-point)
        after (subs text cursor-pos)
        parent (get-in db [:derived :parent-of block-id])
        new-id (str "block-" (random-uuid))
        ;; New block gets unchecked checkbox
        new-text (str "- [ ] " after)]
    [{:op :update-node :id block-id :props {:text before}}
     {:op :create-node :id new-id :type :block :props {:text new-text}}
     {:op :place :id new-id :under parent :at {:after block-id}}
     {:op :update-node
      :id const/session-ui-id
      :props {:editing-block-id new-id
              :cursor-position 6}}]))  ; After "- [ ] "

(defn split-and-increment-number
  "Split block and increment list number in new block."
  [db block-id cursor-pos context]
  (let [text (get-block-text db block-id)
        number (:number context)
        before (subs text 0 cursor-pos)
        after (subs text cursor-pos)
        parent (get-in db [:derived :parent-of block-id])
        new-id (str "block-" (random-uuid))
        new-number (inc number)
        new-text (str new-number ". " after)]
    [{:op :update-node :id block-id :props {:text before}}
     {:op :create-node :id new-id :type :block :props {:text new-text}}
     {:op :place :id new-id :under parent :at {:after block-id}}
     {:op :update-node
      :id const/session-ui-id
      :props {:editing-block-id new-id
              :cursor-position (+ (count (str new-number ". ")))}}]))
```

---

## Component 4: Delete Key (Forward Delete) (ENHANCE EXISTING)

**File:** `src/plugins/editing.cljc`

### Merge with Next (Child First, Then Sibling)

```clojure
(intent/register-intent! :delete-forward
  {:doc "Handle Delete key (forward delete).

         Behaviors:
         - Has text selection → Delete selection
         - At end of block → Merge with next block
         - Middle of text → Delete next character (handles multi-byte)

         Merge priority:
         1. First child (if exists)
         2. Next sibling (if no children)
         3. No-op (if neither exists)"

   :spec [:map
          [:type [:= :delete-forward]]
          [:block-id :string]
          [:cursor-pos :int]
          [:has-selection? :boolean]]

   :handler
   (fn [db {:keys [block-id cursor-pos has-selection?]}]
     (let [text (get-block-text db block-id)
           at-end? (= cursor-pos (count text))]

       (cond
         ;; Has selection - delete selection (handled by component)
         has-selection?
         nil  ; Component handles this

         ;; At end - merge with next
         at-end?
         (let [first-child (get-in db [:derived :first-child-of block-id])
               next-sibling (get-in db [:derived :next-id-of block-id])
               target-id (or first-child next-sibling)]
           (when target-id
             (let [target-text (get-block-text db target-id)
                   merged-text (str text target-text)
                   ;; If merging with child, need to move child's children up
                   target-children (get-in db [:children-by-parent target-id] [])]
               (concat
                ;; Update current block with merged text
                [{:op :update-node :id block-id :props {:text merged-text}}]
                ;; Move target's children to current block
                (map (fn [child-id]
                       {:op :place :id child-id :under block-id :at :last})
                     target-children)
                ;; Delete target block
                [{:op :place :id target-id :under const/root-trash :at :last}]
                ;; Cursor stays at original position (end of original text)
                [{:op :update-node
                  :id const/session-ui-id
                  :props {:editing-block-id block-id
                          :cursor-position (count text)}}]))))

         ;; Middle of text - delete next character
         :else
         (let [;; Handle multi-byte characters (emoji, CJK)
               next-char-len (grapheme-length text cursor-pos)
               new-text (str (subs text 0 cursor-pos)
                            (subs text (+ cursor-pos next-char-len)))]
           [{:op :update-node :id block-id :props {:text new-text}}
            {:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id block-id
                     :cursor-position cursor-pos}}]))))})

(defn grapheme-length
  "Return length of grapheme cluster at position (handles emoji).

   Example:
     text: 'hi😀there'
     pos: 2
     => 2  (emoji is 2 UTF-16 code units)

   Uses grapheme-splitter library for correctness."
  [text pos]
  ;; TODO: Implement using grapheme-splitter
  ;; For now, simple heuristic: check if surrogate pair
  (let [code-point (.charCodeAt text pos)]
    (if (and (>= code-point 0xD800) (<= code-point 0xDBFF))
      2  ; High surrogate - emoji or CJK takes 2 units
      1)))
```

---

## Component 5: Left/Right Arrow Cross-Boundary (ENHANCE EXISTING)

**File:** `src/components/block.cljs`

### Arrow Handler Enhancement

```clojure
(defn handle-arrow-left [e db block-id on-intent]
  "Handle left arrow key.

   Behaviors:
   - Has text selection → Collapse to start
   - At start of block → Edit previous block at end
   - Middle of text → Move cursor left (browser default)"
  (let [target (.-target e)
        selection (.getSelection js/window)
        has-selection? (not= (.-anchorOffset selection) (.-focusOffset selection))
        at-start? (and (= (.-anchorOffset selection) 0)
                      (= (.-anchorNode selection) (.-firstChild target)))]
    (cond
      ;; Has selection - collapse to start
      has-selection?
      (do (.preventDefault e)
          (.collapseToStart selection))

      ;; At start - navigate to previous block
      at-start?
      (do (.preventDefault e)
          (on-intent {:type :navigate-to-adjacent
                     :direction :up
                     :current-block-id block-id
                     :cursor-position :max}))  ; Enter previous at end

      ;; Middle - let browser handle
      :else nil)))

(defn handle-arrow-right [e db block-id on-intent]
  "Handle right arrow key.

   Behaviors:
   - Has text selection → Collapse to end
   - At end of block → Edit next block at start
   - Middle of text → Move cursor right (browser default)"
  (let [target (.-target e)
        text-content (.-textContent target)
        selection (.getSelection js/window)
        has-selection? (not= (.-anchorOffset selection) (.-focusOffset selection))
        at-end? (= (.-anchorOffset selection) (count text-content))]
    (cond
      ;; Has selection - collapse to end
      has-selection?
      (do (.preventDefault e)
          (.collapseToEnd selection))

      ;; At end - navigate to next block
      at-end?
      (do (.preventDefault e)
          (on-intent {:type :navigate-to-adjacent
                     :direction :down
                     :current-block-id block-id
                     :cursor-position 0}))  ; Enter next at start

      ;; Middle - let browser handle
      :else nil)))
```

### Navigate to Adjacent Block Intent

```clojure
(intent/register-intent! :navigate-to-adjacent
  {:doc "Navigate to adjacent block (for left/right arrows at boundaries).

         Simpler than :navigate-with-cursor-memory - doesn't preserve column.
         Just enters adjacent block at specified position."

   :spec [:map
          [:type [:= :navigate-to-adjacent]]
          [:direction [:enum :up :down]]
          [:current-block-id :string]
          [:cursor-position [:or :int [:enum :max]]]]

   :handler
   (fn [db {:keys [direction current-block-id cursor-position]}]
     (let [target-id (case direction
                      :up (get-in db [:derived :prev-id-of current-block-id])
                      :down (get-in db [:derived :next-id-of current-block-id]))]
       (when target-id
         (let [target-text (get-block-text db target-id)
               actual-pos (if (= cursor-position :max)
                           (count target-text)
                           cursor-position)]
           [{:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id nil}}
            {:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id target-id
                     :cursor-position actual-pos}}]))))})
```

---

## Component 6: Selection Collapse Before Navigate/Delete

**File:** `src/components/block.cljs`

### Universal Selection Check

```clojure
(defn has-text-selection?
  "Check if user has text selected in contenteditable."
  []
  (let [selection (.getSelection js/window)]
    (and (> (.-rangeCount selection) 0)
         (let [range (.getRangeAt selection 0)]
           (not (.-collapsed range))))))

(defn get-selection-bounds
  "Get start/end positions of text selection."
  []
  (when (has-text-selection?)
    (let [selection (.getSelection js/window)
          range (.getRangeAt selection 0)
          start (.-startOffset range)
          end (.-endOffset range)]
      {:start (min start end)
       :end (max start end)})))

;; Enhanced handlers with selection collapse

(defn handle-arrow-up [e db block-id on-intent]
  (let [target (.-target e)
        cursor-pos (detect-cursor-row-position target)]
    (cond
      ;; NEW: If has selection, collapse to start
      (has-text-selection?)
      (let [selection (.getSelection js/window)]
        (.preventDefault e)
        (.collapseToStart selection))

      ;; First row - navigate to previous block
      (:first-row? cursor-pos)
      (do (.preventDefault e)
          (let [text-content (.-textContent target)
                selection (.getSelection js/window)
                cursor-offset (.-anchorOffset selection)]
            (on-intent {:type :navigate-with-cursor-memory
                       :direction :up
                       :current-block-id block-id
                       :current-text text-content
                       :current-cursor-pos cursor-offset})))

      ;; Middle - let browser handle
      :else nil)))

(defn handle-backspace [e db block-id on-intent]
  (let [target (.-target e)
        selection (.getSelection js/window)
        cursor-pos (.-anchorOffset selection)
        at-start? (zero? cursor-pos)]
    (cond
      ;; NEW: If has selection, delete selection (let browser handle)
      (has-text-selection?)
      nil  ; Browser handles selection deletion

      ;; At start - merge with previous or delete block
      at-start?
      (do (.preventDefault e)
          (on-intent {:type :backspace-at-start
                     :block-id block-id}))

      ;; Middle - check for paired deletion
      :else
      (do (.preventDefault e)
          (on-intent {:type :delete-with-pair-check
                     :block-id block-id
                     :cursor-pos cursor-pos})))))

(defn handle-delete [e db block-id on-intent]
  (let [target (.-target e)
        text-content (.-textContent target)
        selection (.getSelection js/window)
        cursor-pos (.-anchorOffset selection)
        at-end? (= cursor-pos (count text-content))
        has-selection? (has-text-selection?)]
    (cond
      ;; NEW: If has selection, delete selection (let browser handle)
      has-selection?
      nil  ; Browser handles selection deletion

      ;; At end - merge with next
      at-end?
      (do (.preventDefault e)
          (on-intent {:type :delete-forward
                     :block-id block-id
                     :cursor-pos cursor-pos
                     :has-selection? false}))

      ;; Middle - let browser handle (or call delete-forward for multi-byte)
      :else nil)))
```

---

## Component 7: Autocomplete Trigger Cleanup (NEW PLUGIN)

**File:** `src/plugins/autocomplete.cljc`

### Trigger State Management

```clojure
(def triggers
  "Characters that open autocomplete menus."
  {"/" :slash-commands    ; Slash commands
   "[[" :page-search      ; Page reference search
   "((" :block-search     ; Block reference search
   "#" :tag-search        ; Tag/page search (hashtag)
   "@" :mention-search})  ; User mention (if implemented)

(defn detect-trigger
  "Check if text ends with a trigger sequence.

   Returns: {:trigger '[[' :type :page-search :pos 42}
   or nil if no trigger detected."
  [text cursor-pos]
  (some (fn [[trigger-str trigger-type]]
          (let [trigger-len (count trigger-str)
                start-pos (- cursor-pos trigger-len)]
            (when (and (>= start-pos 0)
                      (= (subs text start-pos cursor-pos) trigger-str))
              {:trigger trigger-str
               :type trigger-type
               :pos start-pos})))
        triggers))

(intent/register-intent! :check-autocomplete-trigger
  {:doc "Check if text input triggered autocomplete menu.

         Called on every character insertion.
         Opens appropriate autocomplete UI if trigger detected."

   :spec [:map
          [:type [:= :check-autocomplete-trigger]]
          [:block-id :string]
          [:cursor-pos :int]]

   :handler
   (fn [db {:keys [block-id cursor-pos]}]
     (let [text (get-block-text db block-id)
           trigger (detect-trigger text cursor-pos)]
       (when trigger
         [{:op :update-node
           :id const/session-ui-id
           :props {:autocomplete {:type (:type trigger)
                                 :trigger (:trigger trigger)
                                 :block-id block-id
                                 :trigger-pos (:pos trigger)
                                 :search-text ""}}}])))})

(intent/register-intent! :close-autocomplete
  {:doc "Close autocomplete menu and optionally delete trigger.

         Called when:
         - User presses Escape
         - User presses Backspace on trigger character
         - User clicks outside menu"

   :spec [:map
          [:type [:= :close-autocomplete]]
          [:delete-trigger? :boolean]]

   :handler
   (fn [db {:keys [delete-trigger?]}]
     (let [ac (get-in db [:nodes const/session-ui-id :props :autocomplete])
           block-id (:block-id ac)
           trigger-pos (:trigger-pos ac)
           trigger-len (count (:trigger ac))]
       (if delete-trigger?
         ;; Delete trigger and close menu
         (let [text (get-block-text db block-id)
               new-text (str (subs text 0 trigger-pos)
                            (subs text (+ trigger-pos trigger-len)))]
           [{:op :update-node :id block-id :props {:text new-text}}
            {:op :update-node
             :id const/session-ui-id
             :props {:autocomplete nil
                     :editing-block-id block-id
                     :cursor-position trigger-pos}}])
         ;; Just close menu
         [{:op :update-node
           :id const/session-ui-id
           :props {:autocomplete nil}}])))})
```

---

## Component 8: Multi-byte Character Support

**File:** `src/utils/text.cljc`

### Grapheme Cluster Handling

```clojure
(defn grapheme-length-at
  "Get length of grapheme cluster at position (handles emoji, CJK).

   Uses grapheme-splitter library for Unicode correctness.

   Example:
     text: 'Hello😀World'
     pos: 5
     => 2  (emoji takes 2 UTF-16 code units)

   Returns: Integer (1 for ASCII, 2+ for emoji/CJK)"
  [text pos]
  #?(:cljs
     (let [splitter (js/GraphemeSplitter.)]
       ;; Get grapheme at position
       (let [graphemes (.splitGraphemes splitter text)
             ;; Count UTF-16 code units up to pos
             chars-before (take-while
                           (fn [[idx _]]
                             (< idx pos))
                           (map-indexed vector text))
             grapheme-idx (count chars-before)
             grapheme (nth graphemes grapheme-idx)]
         (count grapheme)))
     :clj
     ;; On JVM, use Character.charCount
     (let [code-point (.codePointAt text pos)]
       (Character/charCount code-point))))

(defn count-graphemes
  "Count grapheme clusters in string (not UTF-16 code units).

   Example:
     text: 'Hi😀'
     => 3  (not 4)"
  [text]
  #?(:cljs
     (let [splitter (js/GraphemeSplitter.)]
       (count (.splitGraphemes splitter text)))
     :clj
     (.codePointCount text 0 (count text))))

(defn cursor-pos-to-grapheme-index
  "Convert UTF-16 cursor position to grapheme index.

   Useful for cursor positioning that respects emoji."
  [text cursor-pos]
  #?(:cljs
     (let [splitter (js/GraphemeSplitter.)
           graphemes (.splitGraphemes splitter text)
           utf16-pos (atom 0)]
       (count (take-while
               (fn [grapheme]
                 (let [len (count grapheme)
                       at-pos @utf16-pos]
                   (swap! utf16-pos + len)
                   (< at-pos cursor-pos)))
               graphemes)))
     :clj
     (loop [idx 0
            utf16-pos 0]
       (if (>= utf16-pos cursor-pos)
         idx
         (let [code-point (.codePointAt text utf16-pos)
               char-count (Character/charCount code-point)]
           (recur (inc idx) (+ utf16-pos char-count)))))))
```

---

## Component 9: Cursor Position Guarantees

### After Structural Operations

```clojure
;; Indent/Outdent → cursor at START (position 0)
(intent/register-intent! :indent-selected
  {:handler (fn [db _]
              (let [ops (apply-to-active-targets db indent-ops)
                    ;; Add cursor reset for editing block
                    editing-id (q/editing-block-id db)]
                (if editing-id
                  (concat ops
                          [{:op :update-node
                            :id const/session-ui-id
                            :props {:cursor-position 0}}])
                  ops)))})

;; Move block → LOSE edit mode (exit to selection)
(intent/register-intent! :move-selected-up
  {:handler (fn [db _]
              (let [ops (move-selected-up-ops db)]
                (concat
                 ;; Exit edit mode
                 [{:op :update-node
                   :id const/session-ui-id
                   :props {:editing-block-id nil}}]
                 ops)))})

;; Backspace merge → cursor at MERGE POINT (end of previous text)
;; Already implemented in :merge-with-prev (editing.cljc:256)

;; Delete merge → cursor STAYS at original position
;; Already implemented in :delete-forward above
```

---

## Component 10: Undo/Redo with Debounced Checkpoints

**Files:** `src/kernel/history.cljc`, `src/shell/blocks_ui.cljs`, `src/components/block.cljs`

### Problem Statement

**Current Issue:** Browser's contenteditable maintains its own undo stack, which conflicts with kernel history:

```clojure
// User types "hello world" in contenteditable
// Browser undo stack: ["h", "he", "hel", ..., "hello world"]
// Kernel history: Gets :update-content intent every keystroke

// User presses Cmd+Z:
// Option A: Browser handles it → DOM changes but DB unchanged → DESYNC
// Option B: Kernel handles it → DB changes but browser undo stack still active → CONFLICT
```

**Solution:** Disable browser undo + debounced checkpoint system for text changes.

### Architecture Changes

**1. Disable Browser Undo Stack**

```clojure
;; components/block.cljs - Add to contenteditable element
[:span.content-edit
 {:contentEditable true
  :on {:beforeinput (fn [e]
                      ;; Block browser undo/redo from building history
                      (when (contains? #{"historyUndo" "historyRedo"} (.-inputType e))
                        (.preventDefault e)))
       :input (fn [e] ...)}}]
```

**2. Filter Text Changes from History Recording**

```clojure
;; kernel/transaction.cljc - Add history filter
(defn should-record-in-history?
  "Determine if an intent should create a history entry.

   Filters out ephemeral state changes that shouldn't be undoable:
   - :update-content (too granular - use checkpoints instead)
   - :selection (ephemeral UI state)
   - :enter-edit/:exit-edit (ephemeral UI state)

   All structural changes (split, merge, delete, move, indent) record automatically."
  [intent]
  (case (:type intent)
    ;; Ephemeral - don't record
    :update-content false
    :selection false
    :enter-edit false
    :exit-edit false

    ;; Structural - always record
    (:split-at-cursor :merge-with-prev :merge-with-next
     :delete :indent :outdent :move-up :move-down
     :create-and-enter-edit :toggle-checkbox :toggle-fold) true

    ;; Checkpoint - always record
    :checkpoint true

    ;; Default - record
    true))

;; Update apply-intent to use filter
(defn apply-intent [db intent]
  (let [ops (intent/handle db intent)
        new-db (ops/apply-ops db ops)]
    (if (should-record-in-history? intent)
      (H/record new-db)
      new-db)))
```

**3. Add Checkpoint Intent**

```clojure
;; plugins/editing.cljc
(intent/register-intent! :checkpoint
  {:doc "Record current DB state to history (manual checkpoint).

         Used for debounced text editing history.
         Creates history entry without changing DB state."
   :handler (fn [db _intent]
              ;; Return empty ops - just trigger history.record via should-record?
              [])})
```

**4. Debounced Checkpoint in Component**

```clojure
;; components/block.cljs - Enhanced input handler
(defn Block [{:keys [db block-id depth on-intent ...]}]
  (let [;; Component-local state for debounce timer
        checkpoint-timer (atom nil)]
    [:div.block
     ;; ... block structure ...
     [:span.content-edit
      {:contentEditable true
       :on {:beforeinput (fn [e]
                           ;; Prevent browser undo/redo
                           (when (contains? #{"historyUndo" "historyRedo"} (.-inputType e))
                             (.preventDefault e)))
            :input (fn [e]
                     (let [new-text (.. e -target -textContent)]
                       ;; Update DB immediately (responsive UI)
                       (on-intent {:type :update-content
                                  :block-id block-id
                                  :text new-text})

                       ;; Clear existing checkpoint timer
                       (when @checkpoint-timer
                         (js/clearTimeout @checkpoint-timer))

                       ;; Schedule checkpoint after 500ms of no typing
                       (reset! checkpoint-timer
                               (js/setTimeout
                                (fn []
                                  (on-intent {:type :checkpoint})
                                  (reset! checkpoint-timer nil))
                                500))))}}]]))
```

### Behavior

**Typing Experience:**
```clojure
// User types "hello world"
t=0ms:    Type "h" → :update-content (not recorded) + schedule checkpoint(500ms)
t=100ms:  Type "e" → :update-content (not recorded) + cancel previous + schedule(500ms)
t=200ms:  Type "l" → :update-content (not recorded) + cancel previous + schedule(500ms)
...
t=1000ms: Type "d" → :update-content (not recorded) + cancel previous + schedule(500ms)
t=1500ms: [500ms pause] → :checkpoint fires → history.record

// History stack now has: ["hello world"]
// User types more...
t=1600ms: Type " " → :update-content + schedule(500ms)
t=1700ms: Type "f" → :update-content + schedule(500ms)
...
t=2500ms: [500ms pause] → :checkpoint → history.record

// History stack: ["hello world", "hello world foo"]
```

**Undo Behavior:**
```clojure
// User presses Cmd+Z
→ Kernel undo triggers
→ DB reverts to "hello world" (previous checkpoint)
→ Component re-renders with old text
→ Browser contenteditable shows "hello world"
→ No desync!
```

**Structural Changes Still Record Immediately:**
```clojure
// User types "hello" then presses Enter (split)
→ :update-content "hello" (not recorded)
→ :split-at-cursor (RECORDED - structural change)
→ History: [initial-state, after-split]

// User presses Cmd+Z
→ Undo split
→ Back to single block with "hello"
```

### Edge Cases

**1. Quick Undo Before Checkpoint**
```clojure
// User types "hello" then immediately Cmd+Z (before 500ms checkpoint)
→ No checkpoint exists yet
→ Undo reverts to last structural change (e.g., previous block split)
→ User loses "hello" (expected - they didn't pause typing)
```

**2. Multiple Blocks Editing**
```clojure
// User edits block A, switches to block B, edits block B
→ Block A: :exit-edit (not recorded)
→ Block B: :enter-edit (not recorded)
→ Block B gets its own checkpoint timer
→ Each block's text changes checkpoint independently
```

**3. Checkpoint During Autocomplete**
```clojure
// User types "[[hello" → autocomplete opens
→ Checkpoint timer running (500ms from last char)
→ User selects page from menu before checkpoint
→ :close-autocomplete + :insert-page-ref (structural - recorded)
→ Checkpoint timer cleared (autocomplete closed = interaction done)
```

### Testing

```clojure
;; test/kernel/history_checkpoint_test.cljc
(deftest debounced-checkpoint-test
  (testing "Text changes don't record until checkpoint"
    (let [db (-> (sample-db)
                 (tx/apply-intent {:type :update-content :block-id "a" :text "hello"})
                 (tx/apply-intent {:type :update-content :block-id "a" :text "hello world"}))]
      ;; No history entries yet (update-content filtered)
      (is (= 0 (count (get-in db [:history :past]))))

      ;; Checkpoint records current state
      (let [db2 (tx/apply-intent db {:type :checkpoint})]
        (is (= 1 (count (get-in db2 [:history :past]))))
        (is (= "hello world" (get-in (H/undo db2) [:nodes "a" :props :text])))))))

(deftest structural-changes-still-record-test
  (testing "Split/merge/delete record immediately"
    (let [db (-> (sample-db)
                 (tx/apply-intent {:type :update-content :block-id "a" :text "hello"})
                 (tx/apply-intent {:type :split-at-cursor :block-id "a" :cursor-pos 5}))]
      ;; Split recorded (structural change)
      (is (= 1 (count (get-in db [:history :past]))))
      (is (= 2 (count (q/all-blocks (H/undo db)))))))) ;; Undo split → 1 block
```

### Manual Testing (Browser)

**Smoke Test:**
1. Open app, create block
2. Type "hello world" slowly (observe each character appears)
3. Wait 1 second (checkpoint timer expires)
4. Press Cmd+Z
5. ✅ Text reverts to empty (before typing)
6. Press Cmd+Shift+Z (redo)
7. ✅ Text reappears as "hello world"

**Multi-Checkpoint Test:**
1. Type "hello", wait 1s
2. Type " world", wait 1s
3. Type " foo", wait 1s
4. Press Cmd+Z 3 times
5. ✅ Reverts: "hello world foo" → "hello world" → "hello" → ""

**Structural Operation Test:**
1. Type "hello world"
2. Press Enter immediately (before checkpoint)
3. New block created
4. Press Cmd+Z
5. ✅ Split undone, back to "hello world" in single block
6. Press Cmd+Z again
7. ✅ Text reverts to empty (no checkpoint existed for "hello world")

---

## Component 11: Shift+Arrow Multi-Block Selection at Editing Boundaries

**File:** `src/shell/blocks_ui.cljs`

### Problem Statement

**Current Behavior:** Shift+Arrow in non-editing mode extends selection (✅ working). Shift+Arrow WHILE EDITING is blocked:

```clojure
;; blocks_ui.cljs:56-100 - Current arrow boundary handlers
;; ArrowUp at start → exit edit, navigate up, enter edit at end
;; ArrowDown at end → exit edit, navigate down, enter edit at start

// But what about Shift+ArrowUp at start?
// Currently: Does nothing (or browser text selection inside block)
// Expected: Extend block selection to include previous block
```

**Expected Logseq Behavior:**

```clojure
// User editing block B, cursor at start
// Presses Shift+ArrowUp
→ Block A and B both selected (multi-block selection)
→ Edit mode PRESERVED on block B
→ Visual: Both blocks highlighted, cursor still in B

// User presses Shift+ArrowUp again
→ Block A, B, and C selected
→ Still editing B
```

### Implementation

**Enhanced Boundary Detection in blocks_ui.cljs**

```clojure
;; shell/blocks_ui.cljs - Add to handle-global-keydown
(defn handle-global-keydown [e]
  (let [event (keymap/parse-dom-event e)
        db @!db
        key (.-key e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        shift? (.-shiftKey e)
        focus-id (q/focus db)
        editing? (q/editing-block-id db)

        ;; Boundary detection (same as before)
        editable-el (when editing? (.-activeElement js/document))
        at-start? (when (and editable-el ...)
                    ;; ... existing detection ...)
        at-end? (when (and editable-el ...)
                  ;; ... existing detection ...)]

    (cond
      ;; NEW: Shift+ArrowUp at start → Extend selection up
      (and editing? (= key "ArrowUp") at-start? shift? (not mod?))
      (do (.preventDefault e)
          ;; Don't exit edit mode - just extend selection
          (handle-intent {:type :selection :mode :extend-prev}))

      ;; NEW: Shift+ArrowDown at end → Extend selection down
      (and editing? (= key "ArrowDown") at-end? shift? (not mod?))
      (do (.preventDefault e)
          ;; Don't exit edit mode - just extend selection
          (handle-intent {:type :selection :mode :extend-next}))

      ;; Existing: Plain ArrowUp at start (no shift)
      (and editing? (= key "ArrowUp") at-start? (not mod?) (not shift?))
      (do (.preventDefault e)
          ;; ... existing exit-edit + navigate + enter-edit logic ...)

      ;; Existing: Plain ArrowDown at end (no shift)
      (and editing? (= key "ArrowDown") at-end? (not mod?) (not shift?))
      (do (.preventDefault e)
          ;; ... existing exit-edit + navigate + enter-edit logic ...)

      ;; ... rest of keydown handler ...)))
```

### Key Differences

**Plain Arrow (no Shift):**
- Exit edit mode
- Navigate to adjacent block
- Enter edit mode in new block
- Cursor position specified (start/end)

**Shift+Arrow (extend selection):**
- STAY in edit mode
- Extend selection to include adjacent block
- Cursor remains in current editing block
- Both blocks visually highlighted

### Visual Behavior

**Before (editing block B):**
```
[ ] Block A
[█] Block B (editing, cursor at start)
[ ] Block C
```

**After Shift+ArrowUp:**
```
[█] Block A (selected, not editing)
[█] Block B (selected AND editing, cursor still at start)
[ ] Block C
```

**After Shift+ArrowUp again:**
```
[█] Block C (parent of A, selected)
[█] Block A (selected, not editing)
[█] Block B (selected AND editing, cursor still at start)
```

### Interaction with Text Selection

**Inside Block (not at boundary):**
```clojure
// User editing block B, cursor in middle: "hel|lo"
// Presses Shift+ArrowUp
→ Browser text selection (select text within block)
→ No block-level selection

// User at start: "|hello"
// Presses Shift+ArrowUp
→ Block-level selection (extend to include prev block)
→ No text selection
```

**Detection Priority:**
```clojure
(cond
  ;; If cursor at boundary → block-level selection
  (and at-start? shift?)
  (handle-intent {:type :selection :mode :extend-prev})

  ;; Otherwise → browser text selection (default behavior)
  :else nil)  ; Let browser handle
```

### Testing

**Unit Test (Selection State):**
```clojure
(deftest shift-arrow-extend-while-editing-test
  (testing "Shift+ArrowUp at start extends selection"
    (let [db (-> (sample-db)
                 (tx/apply-intent {:type :enter-edit :block-id "b" :cursor-at :start})
                 (tx/apply-intent {:type :selection :mode :extend-prev}))]
      ;; Both blocks selected
      (is (= #{"a" "b"} (q/selection db)))
      ;; Still editing block B
      (is (= "b" (q/editing-block-id db)))
      ;; Focus moved to A (new selection focus)
      (is (= "a" (q/focus db)))))

  (testing "Shift+ArrowDown at end extends selection"
    (let [db (-> (sample-db)
                 (tx/apply-intent {:type :enter-edit :block-id "a" :cursor-at :end})
                 (tx/apply-intent {:type :selection :mode :extend-next}))]
      (is (= #{"a" "b"} (q/selection db)))
      (is (= "a" (q/editing-block-id db)))
      (is (= "b" (q/focus db))))))
```

**Manual Test (Browser):**
1. Create 3 blocks: A, B, C
2. Click block B to select
3. Press Enter to edit
4. Move cursor to start (press Home or Cmd+Left)
5. Press Shift+ArrowUp
6. ✅ Both A and B highlighted (multi-block selection)
7. ✅ Cursor still blinking in B (edit mode preserved)
8. Press Shift+ArrowUp again
9. ✅ A, B, C all highlighted
10. Press Escape
11. ✅ Exit edit mode, selection preserved

**Edge Case - Text Selection vs Block Selection:**
1. Edit block B: "hello world"
2. Cursor in middle: "hel|lo world"
3. Press Shift+ArrowUp
4. ✅ Text selected within block (browser behavior)
5. Press Home to move cursor to start
6. Press Shift+ArrowUp
7. ✅ Block selection extended to previous block

---

## Testing Strategy

### Unit Tests (Pure Logic)

```clojure
;; test/plugins/context_test.cljc
(deftest detect-markup-at-cursor-test
  (testing "Cursor inside bold markup"
    (let [text "Hello **world** test"
          cursor-pos 10]  ; Inside "world"
      (is (= {:type :markup
              :marker "**"
              :markup-type :bold
              :start 6
              :end 15
              :inner-start 8
              :inner-end 13
              :complete? true}
             (context/detect-markup-at-cursor text cursor-pos)))))

  (testing "Cursor outside markup"
    (let [text "Hello **world** test"
          cursor-pos 2]  ; In "Hello"
      (is (nil? (context/detect-markup-at-cursor text cursor-pos))))))

(deftest paired-deletion-test
  (testing "Delete paired brackets"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "[[]]"))
          ops (intent/handle db {:type :delete-with-pair-check
                                 :block-id "a"
                                 :cursor-pos 2})]  ; After "["
      (is (= "" (get-in ops [0 :props :text])))
      (is (= 1 (get-in ops [1 :props :cursor-position])))))

  (testing "Normal backspace when not paired"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "hello"))
          ops (intent/handle db {:type :delete-with-pair-check
                                 :block-id "a"
                                 :cursor-pos 5})]
      (is (= "hell" (get-in ops [0 :props :text])))
      (is (= 4 (get-in ops [1 :props :cursor-position]))))))

(deftest enter-inside-markup-test
  (testing "Enter inside bold exits markup first"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "**hello world**"))
          ops (intent/handle db {:type :context-aware-enter
                                 :block-id "a"
                                 :cursor-pos 5})]  ; After "**hel"
      ;; Should move cursor to after closing **, not split
      (is (= 15 (get-in ops [0 :props :cursor-position]))))))

(deftest delete-forward-merge-priority-test
  (testing "Delete at end merges with first child"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "Parent")
                 (assoc-in [:children-by-parent "a"] ["child"]))
          ops (intent/handle db {:type :delete-forward
                                 :block-id "a"
                                 :cursor-pos 6
                                 :has-selection? false})]
      ;; Should merge with child, not sibling
      (is (= "ParentChild text" (get-in ops [0 :props :text])))
      ;; Cursor stays at original end
      (is (= 6 (get-in ops [2 :props :cursor-position])))))

  (testing "Delete at end merges with sibling if no children"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "First")
                 (assoc-in [:derived :next-id-of "a"] "b"))
          ops (intent/handle db {:type :delete-forward
                                 :block-id "a"
                                 :cursor-pos 5
                                 :has-selection? false})]
      (is (= "FirstSecond" (get-in ops [0 :props :text]))))))

(deftest arrow-with-selection-test
  (testing "Arrow up with selection collapses to start"
    ;; This is component-level, test in browser
    ))

(deftest multi-byte-character-test
  (testing "Delete emoji counts as one grapheme"
    (let [text "Hi😀there"
          pos 2  ; After "Hi", before emoji
          len (text/grapheme-length-at text pos)]
      (is (= 2 len))  ; Emoji is 2 UTF-16 code units
      ))

  (testing "Count graphemes correctly"
    (is (= 3 (text/count-graphemes "Hi😀")))
    (is (= 8 (text/count-graphemes "Hello😀世界")))))
```

### Integration Tests (Component Level)

```clojure
;; test/components/editing_test.cljs
(deftest paired-char-insertion-test
  (testing "Type [ auto-closes to []"
    ;; Mount component
    (let [component (mount-block-editor {:block-id "a" :text ""})]
      ;; Simulate typing "["
      (simulate-keypress component "[")
      ;; Check text is "[]"
      (is (= "[]" (get-editor-text component)))
      ;; Check cursor is between brackets
      (is (= 1 (get-cursor-pos component))))))

(deftest enter-in-code-block-test
  (testing "Enter inside code block inserts newline"
    (let [component (mount-block-editor
                     {:block-id "a"
                      :text "```clojure\n(+ 1 2)\n```"})]
      ;; Position cursor after "(+ 1 "
      (set-cursor-pos component 16)
      ;; Press Enter
      (simulate-keypress component "Enter")
      ;; Should insert newline, not create new block
      (is (= "```clojure\n(+ 1 \n2)\n```" (get-editor-text component)))
      (is (= 17 (get-cursor-pos component))))))
```

### Manual Verification Checklist

**Context Detection:**
- [ ] Type `**bold**` and press Enter inside - cursor exits markup first
- [ ] Type `[[page]]` and press Enter inside - navigates to page
- [ ] Type `((ref))` and press Enter inside - opens in sidebar
- [ ] Type `` ```code``` `` and press Enter inside - inserts newline

**Paired Characters:**
- [ ] Type `[` - auto-closes to `[]` with cursor between
- [ ] Type `]` when next char is `]` - skips over instead of inserting
- [ ] Backspace after `[` when next is `]` - deletes both
- [ ] Type `**` - auto-closes with cursor between

**Delete Key:**
- [ ] Delete at end with child - merges child text (child becomes sibling)
- [ ] Delete at end without child - merges sibling text
- [ ] Delete in middle - removes next character
- [ ] Delete emoji - removes entire emoji (not half)

**Arrow Keys:**
- [ ] Left at start - edits previous block at end
- [ ] Right at end - edits next block at start
- [ ] Arrow with text selection - collapses selection (doesn't navigate)

**List Continuation:**
- [ ] Enter on `1. item` - creates `2. `
- [ ] Enter on `1. ` (empty) - removes number
- [ ] Enter on `- [ ] task` - creates `- [ ] `
- [ ] Enter on `- [ ] ` (empty) - removes checkbox

**Cursor Positioning:**
- [ ] Indent block while editing - cursor moves to start
- [ ] Outdent block while editing - cursor moves to start
- [ ] Move block - exits edit mode
- [ ] Backspace merge - cursor at merge point
- [ ] Delete merge - cursor stays at original position

---

## Success Criteria

✅ **All context-aware Enter behaviors work correctly**
✅ **Paired characters auto-close and delete together**
✅ **Delete key merges with children first, siblings second**
✅ **Left/Right arrows cross block boundaries at start/end**
✅ **Text selection collapses before navigation/deletion**
✅ **Multi-byte characters (emoji, CJK) handled correctly**
✅ **Cursor position is predictable after every operation**
✅ **Autocomplete triggers open/close cleanly**

---

## Implementation Order

### Phase 1: Context Detection (Foundation)
1. Create `src/plugins/context.cljc`
2. Implement markup detection
3. Implement block/page ref detection
4. Implement code block detection
5. Implement list item detection
6. Write unit tests (50+ test cases)

**Test:** Can detect all context types correctly

### Phase 2: Paired Characters
1. Enhance `src/plugins/smart_editing.cljc`
2. Add `:insert-paired-char` intent
3. Add `:delete-with-pair-check` intent
4. Wire to component key handlers
5. Write unit tests

**Test:** Paired characters auto-close and delete together

### Phase 3: Context-Aware Enter
1. Enhance `src/plugins/smart_editing.cljc`
2. Implement `:context-aware-enter` intent
3. Route Enter key through context check
4. Write tests for each context type

**Test:** Enter behaves correctly in all contexts

### Phase 4: Delete Key
1. Enhance `src/plugins/editing.cljc`
2. Implement `:delete-forward` intent
3. Handle child-first merge priority
4. Add multi-byte support
5. Write tests

**Test:** Delete key merges correctly

### Phase 5: Arrow Boundaries
1. Enhance `src/components/block.cljs`
2. Add `handle-arrow-left` and `handle-arrow-right`
3. Implement `:navigate-to-adjacent` intent
4. Wire to keydown handler

**Test:** Arrows cross boundaries at start/end

### Phase 6: Selection Handling
1. Enhance all arrow/delete handlers
2. Add `has-text-selection?` check
3. Collapse selection before navigate

**Test:** Selection collapses, doesn't navigate

### Phase 7: Multi-byte Support
1. Create `src/utils/text.cljc`
2. Integrate grapheme-splitter library
3. Update delete/cursor positioning to use grapheme counts
4. Test with emoji and CJK

**Test:** Emoji and CJK handled correctly

### Phase 8: Cursor Position Guarantees
1. Update structural operation intents
2. Add explicit cursor position in ops
3. Verify in tests

**Test:** Cursor position predictable after every op

---

## Dependencies

```clojure
;; package.json
{
  "dependencies": {
    "grapheme-splitter": "^1.0.4"  ; Unicode grapheme cluster support
  }
}
```

```clojure
;; deps.edn (for JVM/bb)
{:deps
 {com.ibm.icu/icu4j {:mvn/version "72.1"}}}  ; Unicode support on JVM
```

---

## References

**Logseq Source Files:**
- `src/main/frontend/handler/editor.cljs:2490-2554` - Context-aware Enter
- `src/main/frontend/handler/editor.cljs:2679-2704` - Arrow navigation with row detection
- `src/main/frontend/handler/editor.cljs:2743-2790` - Left/Right arrow handlers
- `src/main/frontend/handler/editor.cljs:2792-2848` - Delete key with merge
- `src/main/frontend/handler/editor.cljs:2850-2925` - Backspace with paired deletion
- `src/main/frontend/util/thingatpt.cljs` - Context detection utilities
- `src/main/frontend/util/cursor.cljs` - Cursor positioning helpers
- `src/main/frontend/util.cljc:389` - Line position calculation for cursor memory

**Evo Files to Create/Modify:**
- `src/plugins/context.cljc` - NEW
- `src/plugins/smart_editing.cljc` - ENHANCE
- `src/plugins/editing.cljc` - ENHANCE
- `src/plugins/autocomplete.cljc` - NEW
- `src/utils/text.cljc` - NEW
- `src/components/block.cljs` - ENHANCE
- `test/plugins/context_test.cljc` - NEW
- `test/plugins/smart_editing_test.cljc` - ENHANCE
- `test/components/editing_test.cljs` - NEW
