# Coding Gotchas

Common pitfalls when working in this codebase.

## Constants & IDs

### Root IDs Use Keywords, Not Prefixed

❌ **Wrong:**
```clojure
{:op :place :id target-id :under :root-trash :at :last}
```

✅ **Correct:**
```clojure
{:op :place :id target-id :under :trash :at :last}
```

**Why:** Root constants defined in `src/kernel/constants.cljc`:
- `:doc`, `:trash`, `:session` (not `:root-doc`, `:root-trash`, etc.)
- Only `const/root-trash` is the Clojure var, value is `:trash`

**Fix:** Always grep constants file or use the defined constants:
```bash
rg "def root-" src/kernel/constants.cljc
```

---

### Session State is Separate from DB

❌ **Wrong (outdated):**
```clojure
;; Session state no longer lives in DB nodes!
(get-in db [:nodes "session/ui" :props :editing-block-id])
```

✅ **Correct:**
```clojure
(require '[kernel.query :as q])

;; Query session state using kernel.query functions
(q/editing-block-id session)
(q/selection session)
(q/folded? session "block-id")
```

**Why:** Session state moved to separate atom (`shell/view-state.cljs`).
DB only contains persistent document graph.

**Handler signature:**
```clojure
;; Handlers receive both db and session
(fn [db session intent]
  {:ops [...]                          ; DB operations
   :session-updates {:ui {...}}})      ; Session changes
```

### Query Functions Have Different Signatures

❌ **Wrong (passing session when not expected):**
```clojure
;; In an intent handler with db and session params
(let [next-block (q/next-block-dom-order db session block-id)]  ; WRONG!
  ...)
```

✅ **Correct (check function signature first):**
```clojure
;; q/next-block-dom-order takes [db current-id] - NO session param!
(let [next-block (q/next-block-dom-order db block-id)]
  ...)
```

**Why:** Not all query functions take a session parameter. Some only need the db:
- `q/next-block-dom-order [db current-id]` - no session
- `q/prev-block-dom-order [db current-id]` - no session
- `q/editing-block-id [session]` - session only, no db

**Fix:** Always check the function signature in `src/kernel/query.cljc` before calling.
If you pass the wrong number of arguments, ClojureScript will silently use the wrong value
as a parameter, leading to `null` returns instead of errors.

---

## Naming & Shadowing

### Avoid Shadowing Core Vars

❌ **Wrong:**
```clojure
(fn [db {:keys [char count num]}]  ; shadows clojure.core/char, count, num
  ...)
```

✅ **Correct:**
```clojure
(fn [db {:keys [input-char total-count number]}]
  ...)
```

**Commonly shadowed vars to avoid:**
- `char`, `count`, `num`, `name`, `key`, `val`
- `filter`, `map`, `remove` (less common but still shadowed)

**Detection:**
- `bb lint` will warn about shadowed vars
- **Pre-commit hook blocks commits** with shadowed vars (bypass with `--no-verify` if needed)

---

## Multi-Character Markers

### Paired Deletion Needs Length-Sorted Iteration

❌ **Wrong (only handles single chars):**
```clojure
(when (and (= (str (nth text (dec cursor-pos))) opening)
           (= (str (nth text cursor-pos)) closing))
  ;; Delete both - BREAKS with ** or __
  ...)
```

✅ **Correct:**
```clojure
(some (fn [[opening closing]]
        (let [open-len (count opening)
              start-pos (- cursor-pos open-len)]
          (when (and (>= start-pos 0)
                    (= (subs text start-pos cursor-pos) opening)
                    (= (subs text cursor-pos (+ cursor-pos close-len)) closing))
            ...)))
      (sort-by (comp - count key) pairs))  ; Longest first!
```

**Why:** `**bold**` has 2-char markers - need substring matching, not single char

---

## Dataspex State Inspection

### track-changes? Causes Memory Leaks

❌ **Wrong:**
```clojure
;; This accumulates unbounded change history!
(dataspex/inspect "App DB" !db {:track-changes? true})
```

✅ **Correct:**
```clojure
;; No options = no change tracking = no leak
(dataspex/inspect "App DB" !db)
```

**Symptoms:** App slows to halt after heavy clipboard/editing use. Memory grows unboundedly.

**Why:** `{:track-changes? true}` records every state change forever. With high-frequency operations (typing, paste, undo/redo), this accumulates rapidly.

**Fix:** Remove `:track-changes?` option. If you need change tracking, implement bounded history yourself.

### Dispatch Log Stores Full DB Snapshots

❌ **Wrong:**
```clojure
;; Every log entry stores full before/after DB = 400 DB copies for 200 entries!
(let [entry {:intent intent
             :db-before db-before    ; Full DB snapshot
             :db-after db-after      ; Full DB snapshot
             :summary ...}]
  (swap! !log conj entry))
```

✅ **Correct:**
```clojure
;; Store full DB separately (only last one needed for devtools DOM diff)
(reset! !last-db-snapshot {:db-before db-before :db-after db-after})
;; Log entry contains only summaries
(let [entry {:intent intent
             :summary {:before (summarize-db db-before)
                       :after (summarize-db db-after)}}]
  (swap! !log conj entry))
```

**Symptoms:** App becomes jaggy during copy/paste. Frames drop during any high-frequency intent operations.

**Why:** With 200 log entries and each containing 2 full DB snapshots, memory grows to O(200 * 2 * DB_SIZE). Dataspex inspecting this log re-serializes all of it on every intent dispatch.

**Fix:**
1. Store full DB snapshots separately in `!last-db-snapshot` (only the most recent, for devtools)
2. Log entries contain only lightweight summaries
3. Remove Dataspex inspection of logs (access via REPL: `tooling/get-log`)

---

## Tool Usage

### Always Read Before Edit

❌ **Wrong:**
```clojure
(Edit {:file-path "foo.cljs"
       :old-string "  (defn foo"  ; Wrong indentation!
       :new-string "  (defn bar"})
```

✅ **Correct:**
```clojure
(Read {:file-path "foo.cljs"})  ; First!
;; See exact indentation: "    (defn foo" (4 spaces)
(Edit {:file-path "foo.cljs"
       :old-string "    (defn foo"
       :new-string "    (defn bar"})
```

**Why:** Edit requires exact whitespace match including line number prefix

---

## Replicant Components

### Event Handler Syntax

**Always use `:on {:event-name handler}` NOT `:on-event-name handler`**

❌ **Wrong:**
```clojure
[:button {:on-click (fn [e] ...)} "Click"]
```

✅ **Correct:**
```clojure
[:button {:on {:click (fn [e] ...)}} "Click"]
```

**Console warning:** `"Event handler attributes are not supported. Instead of :on-click set :on {:click ,,,}"`

Handler names must match browser events: `:click`, `:keydown`, `:input`, `:blur`, etc.

---

## Testing

### Test ID Format Matters

Tests often fail because of ID format mismatches:

```clojure
;; DB uses string IDs:
{:nodes {"a" {...} "b" {...}}}

;; But test uses keywords:
(is (= :trash (get-in db [:derived :parent-of :block-a])))  ; FAILS - wrong ID format
```

**Fix:** Match the ID format used in test setup

---

## Parentheses in Complex Nested Forms

### Use Editor Bracket Matching

When writing complex `case` or `cond` with nested maps/vectors:

```clojure
(case (:type context)
  :list-item
  (if (:numbered? context)
    (let [...]
      [{:op ...}
       {:op ...}])     ; ← Hard to count!
    (let [...]
      [{:op ...}
       {:op ...}]))    ; ← Which closes what?
  :else
  [...])              ; ← Easy to miscount
```

**Fix:** Use editor's bracket matching (Cmd+Shift+\ in VS Code) before committing

---

## Higher-Order Function Arity Safety

### Uniform Signatures for Op-fns

❌ **Wrong (arity mismatch risk):**
```clojure
;; Different signatures = silent failures when passed to HOF
(defn indent-ops [db id] ...)         ; 2 args
(defn outdent-ops [db session id] ...)  ; 3 args

;; HOF can't know which to call
(mapcat #(op-fn db session %) targets)  ; Breaks indent-ops!
```

✅ **Correct:**
```clojure
;; Uniform signature: all op-fns take (db session id)
(defn indent-ops [db _session id] ...)   ; 3 args, ignores session
(defn outdent-ops [db session id] ...)   ; 3 args, uses session

;; HOF always passes 3 args
(mapcat #(op-fn db session %) targets)   ; Works for both!
```

**Why:** Clojure's dynamic typing + HOFs = arity mismatches fail silently.
`clj-kondo` can't track function arities through `mapcat`, `map`, etc.

**Fix:** Use uniform signatures. See `plugins.structural` namespace docstring.

---

## Derived Index Debugging

### Stale :parent-of Validation Errors

If you see `:anchor-not-sibling` validation errors with messages like:
```
Anchor X is not a sibling under parent Y
```

This indicates `:derived :parent-of` doesn't match `:children-by-parent`.

**Debug assertions are in place to detect corruption:**
- `kernel/intent.cljc:apply-intent` - Checks BEFORE intent processing
- `shell/editor.cljs:handle-intent` - Checks AFTER DB reset
- `shell/nexus.cljs:dispatch-intent` - Checks AFTER Nexus dispatch

**When triggered, you'll see:**
```
🚨🚨🚨 DERIVED INDEX CORRUPTION DETECTED 🚨🚨🚨
Label: after DIRECT dispatch: :context-aware-enter
Inconsistency: {:mismatches [{:child "block-xxx" :expected-parent "task-1" :actual-parent "tasks"}]}
```

**Plus a stack trace** to identify the source.

**Root cause investigation (2025-11):**
- Bug is intermittent - hard to reproduce
- Corruption happens BETWEEN transactions (DB hash changes unexpectedly)
- All dispatch paths go through transaction pipeline which calls `derive-indexes`
- No direct DB modifications found outside transaction pipeline

**If you reproduce the bug:**
1. Check the console for 🚨 corruption messages
2. The label tells you which dispatch path caused it
3. Stack trace shows the call chain
4. Share the full console output for debugging

---

## Quick Reference

### Intent Catalog

_Auto-generated from source. Run `bb lint:intents` to regenerate._

Intents are dispatched via `api/dispatch`. Grouped by plugin:

**Editing:**
| Intent | Description |
|--------|-------------|
| `:enter-edit` | Enter edit mode for a block. Ephemeral - not in un... |
| `:exit-edit` | Exit edit mode WITHOUT selecting block. Ephemeral ... |
| `:exit-edit-and-select` | Exit edit mode and select the block (Logseq parity... |
| `:enter-edit-selected` | Enter edit mode in selected block at end of text (... |
| `:enter-edit-with-char` | Enter edit mode and append a character (type-to-ed... |
| `:clear-cursor-position` | Clear cursor-position from session state. Used aft... |
| `:update-cursor-state` | Update cursor position state for boundary detectio... |
| `:update-content` | Update block text content. |
| `:insert-newline` | Insert a literal newline character at cursor posit... |
| `:merge-with-prev` | Merge block with previous sibling, placing cursor ... |
| `:split-at-cursor` | Split block at cursor position into two blocks. |
| `:delete-forward` | Handle Delete key (forward delete). Behaviors: - H... |
| `:move-cursor-forward-word` | Move cursor to start of next word (Alt+F / Ctrl+Sh... |
| `:move-cursor-backward-word` | Move cursor to start of previous word (Alt+B / Ctr... |
| `:clear-block-content` | Clear entire block content (Cmd+L). Sets text to e... |
| `:kill-to-beginning` | Kill from cursor to beginning of block (Cmd+U). De... |
| `:kill-to-end` | Kill from cursor to end of block (Cmd+K). Deletes ... |
| `:kill-word-forward` | Kill next word (Cmd+Delete). Deletes from cursor t... |
| `:kill-word-backward` | Kill previous word (Alt+Delete / Option+Delete on ... |


**Navigation:**
| Intent | Description |
|--------|-------------|
| `:navigate-with-cursor-memory` | Navigate to adjacent block, preserving cursor colu... |
| `:navigate-to-adjacent` | Navigate to adjacent block (for left/right arrows ... |


**Selection:**
| Intent | Description |
|--------|-------------|
| `:selection` | Unified selection reducer with modes. Modes: - :re... |


**Structure:**
| Intent | Description |
|--------|-------------|
| `:delete` | Delete node by moving to :trash. |
| `:indent` | Indent node under previous sibling. |
| `:outdent` | Outdent node to be sibling of parent. |
| `:create-and-place` | Create new block and place it under parent. |
| `:create-and-enter-edit` | Create new block after focus and immediately enter... |
| `:delete-selected` | Delete all selected nodes (or editing block if no ... |
| `:indent-selected` | Indent all selected nodes (or editing block if no ... |
| `:outdent-selected` | Outdent all selected nodes (or editing block if no... |
| `:move-selected-up` | Move selected nodes up one sibling position. |
| `:move-selected-down` | Move selected nodes down one sibling position. |
| `:move` | Move selection to target parent at anchor position... |
| `:move-block-up-while-editing` | Move current editing block up, preserving edit mod... |
| `:move-block-down-while-editing` | Move current editing block down, preserving edit m... |


**Smart Editing:**
| Intent | Description |
|--------|-------------|
| `:insert-paired-char` | Insert character with auto-closing pair. If openin... |
| `:delete-with-pair-check` | Delete character, removing paired closing char if ... |
| `:merge-with-next` | Merge block with next sibling, delete next block. |
| `:unformat-empty-list` | Remove list marker from empty list item (becomes p... |
| `:split-with-list-increment` | Split block at cursor, incrementing numbered list ... |
| `:toggle-checkbox` | Toggle checkbox state in block text ([ ] <-> [x]). |
| `:smart-split` | Context-aware block splitting on Enter (Logseq par... |
| `:context-aware-enter` | Handle Enter key with full context awareness. Uses... |


**Folding:**
| Intent | Description |
|--------|-------------|
| `:toggle-fold` | Toggle expand/collapse state for a block. |
| `:expand-all` | Recursively expand a block and all descendants. |
| `:collapse` | Collapse a block (hide children). |
| `:toggle-subtree` | Toggle entire subtree (Alt+Click on bullet - Logse... |
| `:toggle-all-folds` | Toggle all folds on a page. Expand all if any coll... |
| `:zoom-in` | Zoom into a block (make it the rendering root). |
| `:zoom-out` | Zoom out to previous level. |
| `:zoom-to` | Zoom to specific block in zoom stack (breadcrumb c... |
| `:reset-zoom` | Reset zoom to root (clear zoom stack). |


**Clipboard:**
| Intent | Description |
|--------|-------------|
| `:paste-text` | Paste text into editing block (Logseq parity). Beh... |
| `:copy-block` | Copy block content to clipboard. Returns nil (actu... |
| `:cut-block` | Cut block (copy + delete). Moves block to trash af... |


---

### Session State Shape

```clojure
;; shell/view-state.cljs atom structure
{:ui {:editing-block-id nil      ; Currently editing block or nil
      :cursor-position nil       ; Pending cursor pos (number, :start, :end)
      :folded #{}                ; Set of folded block IDs
      :zoom-root nil             ; Zoomed block ID or nil
      :cursor {}}                ; Per-block cursor state for boundary detection

 :selection {:nodes #{}          ; Selected block IDs
             :focus nil          ; Last selected (for keyboard nav)
             :anchor nil}        ; Selection anchor (for extend)

 :buffer {:block-id nil          ; Block being buffered
          :text ""               ; Buffer text
          :dirty? false}         ; Has unsaved changes

 :sidebar {:right []}}           ; Sidebar block refs
```

**Query functions** (from `kernel.query`):
```clojure
(q/editing-block-id session)     ; → string or nil
(q/selection session)            ; → #{...} set of IDs
(q/focus session)                ; → focused block ID
(q/folded? session "id")         ; → boolean
(q/zoom-root session)            ; → zoomed ID or nil
```

---

### Derived Indexes

Computed by `kernel.db/derive-indexes` after every transaction:

| Index | Type | Use Case |
|-------|------|----------|
| `:parent-of` | `{child-id → parent-id}` | Find block's parent |
| `:next-id-of` | `{id → next-sibling-id}` | Sibling navigation |
| `:prev-id-of` | `{id → prev-sibling-id}` | Backspace merge target |
| `:index-of` | `{id → position-in-parent}` | Ordering |
| `:pre` | `{id → pre-order-number}` | DOM order traversal |
| `:post` | `{id → post-order-number}` | Bottom-up traversal |
| `:id-by-pre` | `{pre-number → id}` | Reverse lookup |

**When to use which:**
- **Parent lookup:** `(get-in db [:derived :parent-of id])`
- **Next sibling:** `(get-in db [:derived :next-id-of id])`
- **Children:** `(get-in db [:children-by-parent id])` (not derived, direct)
- **DOM order:** Use `navigation/visible-blocks-in-dom-order`

---

### Common Operations Cookbook

**Move block under another:**
```clojure
{:op :place :id "block-a" :under "parent-b" :at :last}
;; :at options: :first, :last, {:before "sibling"}, {:after "sibling"}
```

**Get visible blocks in DOM order:**
```clojure
(require '[kernel.navigation :as nav])
(nav/visible-blocks-in-dom-order db session)  ; → ["id1" "id2" ...]
```

**Check if in edit mode:**
```clojure
(require '[shell.view-state :as session])
(session/editing-block-id)  ; → "block-id" or nil
```

**Dispatch intent from component:**
```clojure
(require '[shell.nexus :as nexus])
(nexus/dispatch! [:editing/split {:block-id id :cursor-pos pos}])
```

**Create block and enter edit:**
```clojure
{:type :create-and-enter-edit :under parent-id :at {:after sibling-id}}
```

---

### Root Constants

From `kernel/constants.cljc`:

| Constant | Value | Use |
|----------|-------|-----|
| `const/root-doc` | `:doc` | Document root |
| `const/root-trash` | `:trash` | Trash root |

**Always use keywords in ops:** `:doc`, `:trash` (not `:root-doc`)

**Session state:** Lives in `shell.view-state` atom, not in DB. Query via `session/editing-block-id`, `session/selection-nodes`, etc.
