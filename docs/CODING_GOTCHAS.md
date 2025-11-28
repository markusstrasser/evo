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

**Always check before using:**
- Constants: `rg "^\\(def " src/kernel/constants.cljc`
- Session queries: `q/selection`, `q/editing-block-id`, `q/folded?` (from `kernel.query`)
- Derived indexes: `:parent-of`, `:next-id-of`, `:prev-id-of` (not `:first-child-of`)
- Root IDs: `:doc`, `:trash` (no prefix)

**Session state lives in separate atom, not DB!**
