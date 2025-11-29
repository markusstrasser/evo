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

**Why:** Session state moved to separate atom (`shell/session.cljs`).
DB only contains persistent document graph.

**Handler signature:**
```clojure
;; Handlers receive both db and session
(fn [db session intent]
  {:ops [...]                          ; DB operations
   :session-updates {:ui {...}}})      ; Session changes
```

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

**Fix:** Use uniform signatures. See `plugins.struct` namespace docstring.

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
- `shell/blocks_ui.cljs:handle-intent` - Checks AFTER DB reset
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

Intents are dispatched via `api/dispatch`. Grouped by domain:

**Editing (enter/exit modes):**
| Intent | Key Params | Effect |
|--------|------------|--------|
| `:enter-edit` | `:block-id`, `:cursor-at` | Enter edit mode |
| `:exit-edit` | — | Exit edit, no selection |
| `:exit-edit-and-select` | — | Exit edit, select block |
| `:enter-edit-selected` | — | Edit focused block at end |
| `:enter-edit-with-char` | `:block-id`, `:char` | Type-to-edit (§7.1) |

**Content modification:**
| Intent | Key Params | Effect |
|--------|------------|--------|
| `:update-content` | `:block-id`, `:text` | Set block text |
| `:split-at-cursor` | `:block-id`, `:cursor-pos` | Split into two blocks |
| `:smart-split` | `:block-id`, `:cursor-pos` | Context-aware Enter |
| `:merge-with-prev` | `:block-id` | Backspace at start |
| `:delete-forward` | `:block-id`, `:cursor-pos` | Delete key |
| `:insert-newline` | `:block-id`, `:cursor-pos` | Shift+Enter |

**Structure (indent/move):**
| Intent | Key Params | Effect |
|--------|------------|--------|
| `:indent` / `:outdent` | `:id` | Single block |
| `:indent-selected` / `:outdent-selected` | — | Selection or editing |
| `:move-selected-up` / `:move-selected-down` | — | Mod+Shift+Arrow |
| `:move` | `:id`, `:under`, `:at` | Arbitrary placement |
| `:delete` / `:delete-selected` | `:id` / — | Move to trash |

**Navigation & Selection:**
| Intent | Key Params | Effect |
|--------|------------|--------|
| `:selection` | `:mode` | `:next`, `:prev`, `:extend-next`, etc. |
| `:navigate-to-adjacent` | `:direction`, `:block-id` | Arrow in edit mode |
| `:navigate-with-cursor-memory` | `:direction` | Up/Down with column memory |

**Folding & Zoom:**
| Intent | Key Params | Effect |
|--------|------------|--------|
| `:toggle-fold` | `:id` | Cmd+. |
| `:collapse` | `:id` | Cmd+, |
| `:expand-all` | `:id` | Expand subtree |
| `:zoom-to` / `:zoom-out` / `:reset-zoom` | `:id` | Zoom navigation |

---

### Session State Shape

```clojure
;; shell/session.cljs atom structure
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
(require '[shell.session :as session])
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
| `const/session-ui-id` | `"session/ui"` | Session snapshot in history |

**Always use keywords in ops:** `:doc`, `:trash` (not `:root-doc`)
