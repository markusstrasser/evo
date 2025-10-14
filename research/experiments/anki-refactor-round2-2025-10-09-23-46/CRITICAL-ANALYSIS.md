# Anki Refactoring Round 2 - Critical Analysis

**Date**: 2025-10-09
**Baseline**: 444 LOC (down from 533 original)
**Agents**: 4x GPT-5 Codex (reasoning-effort=high)

## Summary

After critically reviewing all 4 agent reports against the "only if better than status quo" standard, here's what's **actually worth doing**:

---

## ✅ RECOMMEND: High-Value, Low-Risk

### 1. Remove Redundant `render!` Calls (Agent 2)
**LOC Saved**: -3
**Risk**: None
**Effort**: 5 minutes

**Why**: We already have `(add-watch !state :render ...)` that triggers `render!` on every state change. The manual `render!` calls in `::select-folder`, `::show-answer`, and `::rate-card` are duplicate work.

**Fix**: Just delete lines 136, 141, 158 in `ui.cljs`:
```clojure
;; BEFORE
(swap! !state assoc :screen :review)
(render!)  ;; <-- DELETE (watch already renders)

;; AFTER
(swap! !state assoc :screen :review)
;; Watch handles rendering automatically
```

**Verdict**: ✅ Clear win. No downsides.

---

### 2. Remove Unused `:log []` Key (Agents 1, 3)
**LOC Saved**: -1
**Risk**: None
**Effort**: 2 minutes

**Why**: `reduce-events` initializes `:log []` but `apply-event` never appends to it. Dead code.

**Fix**: Line 144 in `core.cljc`:
```clojure
;; BEFORE
(reduce apply-event {:cards {} :meta {} :log []} events)

;; AFTER
(reduce apply-event {:cards {} :meta {}} events)
```

**Verdict**: ✅ Obvious cleanup.

---

### 3. Fix Error Swallowing in FS Operations (Agent 4)
**LOC Saved**: 0 (improves robustness)
**Risk**: None
**Effort**: 15 minutes

**Why**: `load-log` and `load-cards` catch **all** exceptions and silently return `[]`/`""`. A corrupt file looks identical to a missing file - no way to debug.

**Fix**: Only catch `NotFoundError`, log others:
```clojure
;; fs.cljs:64
(defn load-log [dir-handle]
  (p/catch (p/let [...])
    (fn [e]
      (if (= (.-name e) "NotFoundError")
        []
        (do (js/console.error "Failed to load log:" e)
            (throw e))))))
```

**Verdict**: ✅ Better error visibility with zero downside.

---

### 4. Use `keep` Instead of `filter` + `map` (Agent 3)
**LOC Saved**: -2
**Risk**: None
**Effort**: 5 minutes

**Why**: `due-cards` does two passes when one suffices.

**Fix**: Line 149 in `core.cljc`:
```clojure
;; BEFORE
(->> (:meta state)
     (filter (fn [[_hash meta]] (<= (.getTime (:due-at meta)) now)))
     (map first)
     vec)

;; AFTER
(->> (:meta state)
     (keep (fn [[hash {:keys [due-at]}]]
             (when (<= (.getTime due-at) now) hash)))
     vec)
```

**Verdict**: ✅ Cleaner, fewer traversals.

---

## ⚠️ MAYBE: Moderate Value, Consider Trade-offs

### 5. Extract `markdown->card-index` Helper (Agent 1)
**LOC Saved**: -6
**Risk**: Low (adds indirection)
**Effort**: 20 minutes

**Why**: Avoids recomputing `card-hash` twice per card in `load-and-sync-cards!`.

**Consideration**: Premature optimization? Only matters with 1000+ cards. Current code is clearer inline.

**Verdict**: ⚠️ Skip for now. Optimize when proven slow.

---

### 6. Split `load-and-sync-cards!` into Pure + IO (Agent 4)
**LOC Saved**: -12 (adds testability)
**Risk**: Medium (more files/functions)
**Effort**: 45 minutes

**Why**: Currently mixes parsing, diffing, IO, and state reduction. Harder to test.

**Consideration**: For a 444 LOC codebase, this feels over-engineered. Tests already cover behavior.

**Verdict**: ⚠️ Skip. YAGNI (You Aren't Gonna Need It).

---

## ❌ REJECT: Not Better Than Status Quo

### 7. Card Presenter Registry (Agent 1, 2)
**LOC Impact**: +12 new, -8 old = **+4 LOC**
**Complexity**: Higher (indirection through maps)
**Effort**: 60 minutes

**Why Rejected**:
- Current `case` in `review-card` is **explicit and readable**
- Data-driven registry adds indirection for 3 card types
- Makes layout harder to see at a glance
- Only saves LOC when you have 10+ card types (we have 3)

**Quote from agent**: "Data-drives layout... easier extension for new card types"

**Reality**: We're not building Anki. This is a demo. Explicit code > clever abstraction.

**Verdict**: ❌ Reject. Status quo is better.

---

### 8. Event Handler Map Instead of `case` (Agent 2)
**LOC Impact**: +12 new, -10 old = **+2 LOC**
**Complexity**: Adds indirection
**Effort**: 45 minutes

**Why Rejected**:
- Current `case` statement is **clear and direct**
- Map dispatch hides control flow
- Doesn't help with promise handling (still need p/let)
- No real benefit for 3 actions

**Quote from agent**: "Events stay as data... each handler is a first-class function"

**Reality**: The `case` already treats events as data. Adding a map registry doesn't buy anything here.

**Verdict**: ❌ Reject. Case is cleaner for small action sets.

---

### 9. Human-Readable Scheduler Intervals (Agent 3)
**LOC Impact**: +4 helper, -3 map = **+1 LOC**
**Complexity**: Adds conversion layer
**Effort**: 30 minutes

**Why Rejected**:
- Current millisecond map is **simple and fast**
- Converting `{:minutes 5}` to milliseconds adds ceremony
- The mock algorithm comment already explains "5 min / 10 min"

**Quote from agent**: "Store human units... reads better"

**Reality**: `300000` with a comment is clearer than `(-> 5 (* 60 1000))`.

**Verdict**: ❌ Reject. Milliseconds are fine.

---

### 10. Guard Unknown Ratings in Scheduler (Agent 3)
**LOC Impact**: +3
**Risk**: Low
**Effort**: 10 minutes

**Why Rejected**:
- Unknown ratings **can't happen** in current code (UI only sends valid values)
- Adding guards for impossible states is defensive programming theater
- If needed, add Malli schema instead

**Verdict**: ❌ Reject. YAGNI.

---

### 11. Point-Free `card-with-meta` (Agent 3)
**LOC Saved**: -1
**Readability**: Worse

**Why Rejected**:
```clojure
;; BEFORE (clear)
(when-let [card (get-in state [:cards hash])]
  (assoc card :meta (get-in state [:meta hash])))

;; AFTER (terse)
(some-> (get-in state [:cards hash])
        (assoc :meta (get-in state [:meta hash])))
```

Current version is **more explicit**. `some->` saves 1 line but obscures the nil-check logic.

**Verdict**: ❌ Reject. Clarity > cleverness.

---

### 12. State Transition Helpers (Agent 2)
**LOC Impact**: +8 helpers, -10 inline = **-2 LOC**
**Complexity**: Higher (more functions)
**Effort**: 60 minutes

**Why Rejected**:
- Adds `set-review-loaded`, `finish-review` abstraction layer
- Current inline `assoc` chains are **easy to read**
- Only 3 state transitions - helpers are overkill

**Quote from agent**: "Makes the state machine testable"

**Reality**: State is already testable via `swap!` calls. Adding pure helpers doesn't change that.

**Verdict**: ❌ Reject. Inline is clearer.

---

### 13. Parser Helpers (`matcher`, Trim Unification) (Agent 3)
**LOC Saved**: -6
**Abstraction Cost**: High
**Effort**: 45 minutes

**Why Rejected**:
```clojure
;; AGENT SUGGESTS
{:type :qa
 :parse (matcher qa-pattern #(zipmap [:question :answer] (map str/trim %)))}

;; CURRENT (explicit)
{:type :qa
 :parse (fn [text]
          (when-let [[_ q a] (re-matches qa-pattern text)]
            {:question (str/trim q) :answer (str/trim a)}))}
```

Current version: **immediately understandable**
Proposed version: **requires understanding `matcher`, `zipmap`, destructuring**

For 3 parsers, the abstraction cost exceeds the benefit.

**Verdict**: ❌ Reject. Explicit > clever.

---

## Final Recommendations

### DO THESE (15 minutes total):
1. ✅ Remove redundant `render!` calls (-3 LOC)
2. ✅ Remove unused `:log` key (-1 LOC)
3. ✅ Fix error swallowing in FS (0 LOC, better debugging)
4. ✅ Use `keep` in `due-cards` (-2 LOC)

**Total savings**: -6 LOC, better error handling, clearer code.

### SKIP THESE:
- Card presenter registry (adds complexity)
- Event handler map (case is clearer)
- Human-readable intervals (milliseconds are fine)
- State transition helpers (inline is clearer)
- Parser helpers (explicit > clever)
- Unknown rating guards (YAGNI)
- Point-free refactors (clarity > terseness)
- Splitting load-and-sync (over-engineered)

---

## Key Insight

The agents are pattern-matching against **general Clojure best practices** (data-driven, registries, pure functions), but missing the context:

- **This is a 444 LOC demo app, not production software**
- **We have 3 card types, not 50**
- **We have 3 actions, not 30**
- **Explicit code is easier to understand than clever abstractions**

At this scale, **clarity beats abstraction**.

The current codebase is **already clean**. The only real wins are:
1. Removing dead code (`:log`, redundant `render!`)
2. Fixing error swallowing
3. Micro-optimizations (`keep` vs `filter+map`)

Everything else trades clarity for abstraction without measurable benefit.

---

## Verdict

**Apply refactors 1-4 only. Skip the rest.**

Current: 444 LOC
After: ~438 LOC + better error handling
Time: 15 minutes

**That's a win.**
