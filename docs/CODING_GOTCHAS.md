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

## Quick Reference

**Always check before using:**
- Constants: `rg "^\\(def " src/kernel/constants.cljc`
- Session queries: `q/selection`, `q/editing-block-id`, `q/folded?` (from `kernel.query`)
- Derived indexes: `:parent-of`, `:next-id-of`, `:prev-id-of` (not `:first-child-of`)
- Root IDs: `:doc`, `:trash` (no prefix)

**Session state lives in separate atom, not DB!**
