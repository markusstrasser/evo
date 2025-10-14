# Critical Analysis of Anki Refactoring Round 3

**Context**: Current codebase is 579 LOC (core.cljc: 183, fs.cljs: 206, ui.cljs: 190) and clean. Only recommend changes that are CLEARLY better.

---

## Agent 1: core.cljc

### Suggestion 1: Replace loop/recur with reduce in parse-qa-multiline

**Current code** (lines 10-29):
```clojure
(defn parse-qa-multiline [lines]
  (loop [remaining lines
         question nil
         answer nil]
    (if-let [line (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (str/starts-with? trimmed "q ")
          (recur (rest remaining) (subs trimmed 2) answer)

          (str/starts-with? trimmed "a ")
          (recur (rest remaining) question (subs trimmed 2))

          :else
          (recur (rest remaining) question answer)))
      (when (and question answer)
        {:question (str/trim question)
         :answer (str/trim answer)}))))
```

**Proposed code**:
```clojure
(defn parse-qa-multiline [lines]
  (let [{:keys [question answer]}
        (reduce (fn [acc line]
                  (let [trimmed (str/trim line)]
                    (cond-> acc
                      (str/starts-with? trimmed "q ")
                      (assoc :question (subs trimmed 2))

                      (str/starts-with? trimmed "a ")
                      (assoc :answer (subs trimmed 2)))))
                {}
                lines)]
    (when (and question answer)
      {:question (str/trim question)
       :answer (str/trim answer)})))
```

**Better?**: No

**Reasoning**:
- Current code is explicit and easy to read
- The proposed `cond->` is clever but less obvious
- Current version makes "state machine" pattern clear
- **Bug**: The proposed code doesn't handle the `:else` case (ignoring lines that aren't q/a)
- LOC savings: 4 lines is marginal
- Cognitive load: Current version is simpler to understand at a glance

**Verdict**: ❌ Reject

---

### Suggestion 2: Add medley.core require

**Current code** (lines 1-3):
```clojure
(ns lab.anki.core
  "Core Anki clone data structures and operations"
  (:require [clojure.string :as str]))
```

**Proposed code**:
```clojure
(ns lab.anki.core
  "Core Anki clone data structures and operations"
  (:require [clojure.string :as str]
            [medley.core :as medley]))
```

**Better?**: Only if other suggestions are adopted

**Reasoning**:
- Medley is already in deps.edn
- But adding it without using it is waste
- Contingent on other suggestions being accepted

**Verdict**: ⚠️ Maybe (depends on other suggestions)

---

### Suggestion 3: Use update in apply-event

**Current code** (lines 142-153):
```clojure
:card-created
(let [{:keys [card-hash card]} (:event/data event)]
  (-> state
      (assoc-in [:cards card-hash] card)
      (assoc-in [:meta card-hash] (new-card-meta card-hash))))

:review
(let [{:keys [card-hash rating]} (:event/data event)
      current-meta (get-in state [:meta card-hash])]
  (if current-meta
    (assoc-in state [:meta card-hash] (schedule-card current-meta rating))
    state))
```

**Proposed code**:
```clojure
:card-created
(let [{:keys [card-hash card]} (:event/data event)]
  (-> state
      (update :cards assoc card-hash card)
      (update :meta assoc card-hash (new-card-meta card-hash))))

:review
(let [{:keys [card-hash rating]} (:event/data event)]
  (update state :meta
          #(medley/update-existing % card-hash schedule-card rating)))
```

**Better?**: Mixed

**Reasoning**:
- **Card-created change**: Slightly better. `update :cards assoc` is more composable than `assoc-in`. But the improvement is marginal.
- **Review change**: Worse. `medley/update-existing` is clever but less obvious than the explicit `if current-meta`. The current code clearly shows "do nothing if card doesn't exist", which is important semantics.
- LOC savings: 3 lines
- Trade-off: Saves LOC but hurts clarity in the review case

**Verdict**:
- Card-created: ⚠️ Maybe (marginal win)
- Review: ❌ Reject (hurts clarity)

---

### Suggestion 4: Use medley/filter-vals in due-cards

**Current code** (lines 169-177):
```clojure
(defn due-cards [state]
  (let [now (now-ms)]
    (->> (:meta state)
         (filter (fn [[_hash card-meta]]
                   (<= (.getTime (:due-at card-meta)) now)))
         (map first)
         vec)))
```

**Proposed code**:
```clojure
(defn due-cards [state]
  (let [now (now-ms)]
    (-> (:meta state)
        (medley/filter-vals #(<= (.getTime (:due-at %)) now))
        keys
        vec)))
```

**Better?**: Yes

**Reasoning**:
- `filter-vals` directly expresses intent: "filter by value predicate"
- `keys` is more explicit than `(map first)`
- Thread-first `->` reads better than thread-last `->>` here
- LOC savings: 1 line (marginal)
- **This is actually clearer and more idiomatic**

**Verdict**: ✅ Apply

---

### Suggestion 5: Use medley/assoc-some in card-with-meta

**Current code** (lines 179-183):
```clojure
(defn card-with-meta [state card-hash]
  (when-let [card (get-in state [:cards card-hash])]
    (assoc card :meta (get-in state [:meta card-hash]))))
```

**Proposed code**:
```clojure
(defn card-with-meta [state card-hash]
  (when-let [card (get-in state [:cards card-hash])]
    (medley/assoc-some card :meta (get-in state [:meta card-hash]))))
```

**Better?**: No

**Reasoning**:
- This function is called in the UI where meta is ALWAYS expected to exist
- `assoc-some` doesn't buy us anything here
- The "avoid `{:meta nil}`" benefit is theoretical - we'd see it in tests if it mattered
- LOC impact: 0 (no savings)
- Adds dependency for no real benefit

**Verdict**: ❌ Reject

---

## Agent 2: fs.cljs

### Suggestion 1: IndexedDB request helper

**Current code** (multiple locations, ~18 LOC of duplicate IDB request handling):
```clojure
(p/create
 (fn [resolve reject]
   (let [request (.open js/indexedDB db-name db-version)]
     ...
     (set! (.-onsuccess request) #(resolve (.. % -target -result)))
     (set! (.-onerror request) #(reject (.. % -target -error))))))
```

**Proposed code**:
```clojure
(defn- idb-request->promise [request]
  (p/create (fn [resolve reject]
              (set! (.-onsuccess request) #(resolve (.. % -target -result)))
              (set! (.-onerror request)  #(reject  (.. % -target -error))))))

(defn- open-db []
  (let [request (.open js/indexedDB db-name db-version)]
    (set! (.-onupgradeneeded request) upgrade-handler)
    (idb-request->promise request)))
```

**Better?**: Yes

**Reasoning**:
- DRY: Eliminates ~18 LOC of duplication across `open-db`, `save-dir-handle`, `load-dir-handle`
- The pattern is repeated 3+ times (meets threshold)
- IndexedDB ceremony is inherently verbose - this abstraction helps
- **Clear win for maintainability**
- Net LOC savings: ~12 lines

**Verdict**: ✅ Apply

---

### Suggestion 2: Wait for IndexedDB writes in save-dir-handle

**Current code** (lines 170-179):
```clojure
(defn save-dir-handle [handle]
  (js/console.log "Saving directory handle...")
  (p/let [db (open-db)
          tx (.transaction db #js [store-name] "readwrite")
          store (.objectStore tx store-name)
          _ (.put store handle "dir-handle")]
    (js/console.log "Directory handle saved")
    true))
```

**Proposed code**:
```clojure
(p/let [db (open-db)
        tx (.transaction db #js [store-name] "readwrite")
        store (.objectStore tx store-name)]
  (p/let [_ (idb-request->promise (.put store handle "dir-handle"))]
    (js/console.log "Directory handle saved")
    true))
```

**Better?**: Yes

**Reasoning**:
- **Bug fix**: Current code doesn't wait for `.put` to complete
- Could log "saved" before actually saved
- Using the helper (from Suggestion 1) makes this correct
- LOC impact: neutral once helper exists
- **This is a correctness improvement**

**Verdict**: ✅ Apply (contingent on Suggestion 1)

---

### Suggestion 3: Flatten directory walk safely

**Current code** (lines 94-122):
```clojure
(p/resolved (->> results
                 flatten
                 (remove nil?)
                 vec))
```

**Proposed code**:
```clojure
(p/resolved
 (->> results
      (keep identity)
      (into [] cat)))
```

**Better?**: Yes

**Reasoning**:
- **Bug fix**: Current `flatten` tears apart maps if they're nested
- `(into [] cat)` is the correct way to flatten one level
- `(keep identity)` is more idiomatic than `(remove nil?)`
- This is a correctness improvement
- Requires wrapping file results in vectors

**Verdict**: ✅ Apply

---

### Suggestion 4: Narrow error recovery in load-log

**Current code** (lines 65-72):
```clojure
(defn load-log [dir-handle]
  (p/catch
    (read-edn-file dir-handle "log.edn")
    (fn [_e]
      ;; If file doesn't exist, return empty log
      [])))
```

**Proposed code**:
```clojure
(p/recover (read-edn-file dir-handle "log.edn")
           (fn [err]
             (when (= "NotFoundError" (.-name err))
               [])))
```

**Better?**: No

**Reasoning**:
- **Over-engineering**: The current behavior (return [] on any error) is fine for a prototype
- If `log.edn` is corrupted, returning `[]` is reasonable - start fresh
- Letting parse errors bubble up would be confusing for users
- The "hide real errors" concern is theoretical
- If we wanted better errors, we'd need more UI work anyway

**Verdict**: ❌ Reject

---

## Agent 3: ui.cljs

### Suggestion 1: Separate card rendering concerns (card->view helper)

**Current code** (lines 32-67):
```clojure
(defn review-card [{:keys [card show-answer?]}]
  (let [{:keys [front back class-name]}
        (case (:type card)
          :qa {...}
          :cloze {...}
          :image-occlusion {...})]
    [:div.review-card {:class class-name}
     front
     (when show-answer? back)
     (if show-answer?
       (rating-buttons)
       [:button {:on {:click [::show-answer]}} "Show Answer"])]))
```

**Proposed code**:
```clojure
(defn card->view [{:keys [type] :as card} show-answer?]
  (case type
    :qa {:front [...] :back [...] :class "qa-card"}
    :cloze {:front [...] :class "cloze-card"}
    :image-occlusion {:front [...] :back [...] :class "image-occlusion-card"}))

(defn review-card [{:keys [card show-answer?]}]
  (let [{:keys [front back class]} (card->view card show-answer?)]
    [:div.review-card {:class class}
     front
     (when show-answer? back)
     (if show-answer? (rating-buttons) show-answer-button)]))
```

**Better?**: No

**Reasoning**:
- Current code is already well-organized with clear separation
- The proposed `card->view` function returns data structures that are immediately deconstructed
- **Doesn't actually reduce complexity** - just moves it
- If we wanted better abstraction, we'd use multimethods or protocols (mentioned in Agent 4)
- LOC impact: neutral or slightly more
- For 3 card types, the current inline approach is fine

**Verdict**: ❌ Reject

---

### Suggestion 2: Centralize swap! + render! with apply-state!

**Current code** (multiple locations):
```clojure
(swap! !state assoc :show-answer? true)
(render!)
```

**Proposed code**:
```clojure
(defn apply-state! [f & args]
  (apply swap! !state f args)
  (render!))

;; usage
::show-answer
(apply-state! assoc :show-answer? true)
```

**Better?**: No

**Reasoning**:
- **There's already a watch that calls render!** (line 173)
- The explicit `render!` calls are redundant but harmless
- The real fix is to remove the explicit `render!` calls, not wrap them in a helper
- `apply-state!` obscures what's happening
- Doesn't address the root issue (redundant render calls)

**Verdict**: ❌ Reject (but removing explicit render! calls is valid)

---

### Suggestion 3: Tighten ::rate-card destructuring

**Current code** (lines 142-157):
```clojure
(let [rating (first args)
      state (:state @!state)
      dir-handle (:dir-handle @!state) ...]
```

**Proposed code**:
```clojure
(let [{:keys [state dir-handle]} @!state
      rating (first args)
      due (core/due-cards state)
      current-hash (first due)]
  ...)
```

**Better?**: Yes

**Reasoning**:
- Destructuring once is cleaner than multiple `(:key @!state)` calls
- More idiomatic Clojure
- Slightly more readable
- LOC impact: neutral
- **Minor improvement but clear win**

**Verdict**: ✅ Apply

---

### Suggestion 4: Separate p/let from pure transformations in load-and-sync-cards!

**Current code** (lines 100-121):
```clojure
(p/let [markdown (fs/load-cards dir-handle)
        lines (str/split-lines markdown)
        parsed-cards (keep core/parse-card lines)
        events (fs/load-log dir-handle)
        current-state (core/reduce-events events)
        existing-hashes (set (keys (:cards current-state)))
        new-cards (remove #(contains? existing-hashes (core/card-hash %)) parsed-cards)
        new-events (mapv ... new-cards)]
  ...)
```

**Proposed code**:
```clojure
(p/let [markdown (fs/load-cards dir-handle)
        events   (fs/load-log dir-handle)]
  (let [lines (str/split-lines markdown)
        parsed (keep core/parse-card lines)
        current (core/reduce-events events)
        existing (set (keys (:cards current)))
        new-cards (remove #(contains? existing (core/card-hash %)) parsed)
        new-events (mapv #(core/card-created-event (core/card-hash %) %) new-cards)]
    ...))
```

**Better?**: Yes

**Reasoning**:
- Separates async operations from pure transformations
- Makes it clear there are only 2 I/O operations
- More idiomatic Promesa usage
- LOC impact: neutral
- **Clearer separation of concerns**

**Verdict**: ✅ Apply

---

## Agent 4: Cross-Cutting

### Suggestion 1: Remove redundant render! calls (watch already handles it)

**Current situation** (ui.cljs lines 139, 157):
```clojure
(swap! !state assoc :show-answer? true)
(render!)  ;; REDUNDANT - watch on line 173 already renders

;; line 173:
(add-watch !state :render (fn [_ _ _ _] (render!)))
```

**Better?**: Yes

**Reasoning**:
- **This is a legitimate bug**: Every state change renders 2x (explicit + watch)
- The watch is the right approach
- Remove explicit `render!` calls
- LOC savings: ~3 calls removed
- **Clear correctness improvement**

**Verdict**: ✅ Apply

---

### Suggestion 2: Add bang suffix to effectful fs functions

**Current code** (fs.cljs):
```clojure
append-to-log, save-cards, save-dir-handle, load-dir-handle
```

**Proposed code**:
```clojure
append-to-log!, save-cards!, save-dir-handle!, load-dir-handle!
```

**Better?**: No

**Reasoning**:
- The bang convention is for functions that mutate Clojure state (atoms, refs, etc.)
- These functions perform I/O, which is different
- If we followed this rule, ALL I/O functions would need bangs (confusing)
- Common convention: I/O functions don't use bang
- The docstrings and names are clear enough

**Verdict**: ❌ Reject

---

### Suggestion 3: Extract current-date helper

**Current code** (core.cljc, repeated 3 times):
```clojure
(date-from-ms (now-ms))
```

**Proposed code**:
```clojure
(defn current-date []
  (date-from-ms (now-ms)))

;; usage
:created-at (current-date)
:due-at (current-date)
```

**Better?**: No

**Reasoning**:
- Only saves typing 2 characters: `()` vs `(now-ms)`
- Used 3 times (barely meets threshold)
- The pattern `(date-from-ms (now-ms))` is clear and obvious
- Adding a function for this is over-abstraction
- Doesn't meaningfully reduce complexity

**Verdict**: ❌ Reject

---

### Suggestion 4: Dedupe parsed-cards with medley/distinct-by

**Current code** (ui.cljs):
```clojure
(let [parsed-cards (keep core/parse-card lines)
      existing-hashes (set (keys (:cards current-state)))
      new-cards (remove #(contains? existing-hashes (core/card-hash %)) parsed-cards)]
  ...)
```

**Proposed code**:
```clojure
(require '[medley.core :as m])
(let [parsed-cards (->> markdown str/split-lines
                       (keep core/parse-card)
                       (m/distinct-by core/card-hash))]
  ...)
```

**Better?**: Maybe

**Reasoning**:
- **The concern is valid**: Duplicate cards in one import could create duplicate events
- But is this a real problem? Markdown files shouldn't have duplicates
- If they do, event sourcing handles it (existing-hashes check)
- `distinct-by` adds complexity for an edge case
- **Trade-off**: Correctness vs. simplicity

**Verdict**: ⚠️ Maybe (edge case protection, but probably overkill)

---

### Suggestion 5: Remove unused :log [] in reduce-events

**Current code** (core.cljc lines 158-165):
```clojure
(defn reduce-events [events]
  (reduce apply-event
          {:cards {}
           :meta {}
           :log []}  ;; UNUSED
          events))
```

**Proposed code**:
```clojure
(defn reduce-events [events]
  (reduce apply-event
          {:cards {}
           :meta {}}
          events))
```

**Better?**: Yes

**Reasoning**:
- **Dead code**: `:log []` is never used anywhere
- Removing it is pure cleanup
- LOC savings: 1 line
- No downside

**Verdict**: ✅ Apply

---

### Suggestion 6: Shared card-type registry for parsing + rendering

**Current situation**:
- Card types defined in core.cljc `card-parsers` (lines 31-49)
- Card rendering logic duplicated in ui.cljs `review-card` (lines 32-67)
- Adding a new card type requires touching both files

**Proposed**: Multimethod or extended registry

**Better?**: No

**Reasoning**:
- **Over-engineering for 3 card types**
- Current approach is simple and works
- A registry would add complexity (metadata or multimethods)
- The coupling is acceptable for a 579 LOC prototype
- If we had 10+ card types, this would make sense
- **YAGNI**: You aren't gonna need it

**Verdict**: ❌ Reject

---

## Summary

### ✅ Apply (Clear Wins)

1. **core.cljc**: Use `medley/filter-vals` in `due-cards` (+1 clarity, -1 LOC)
2. **fs.cljs**: Extract `idb-request->promise` helper (-12 LOC, +DRY)
3. **fs.cljs**: Wait for IndexedDB writes in `save-dir-handle` (correctness)
4. **fs.cljs**: Fix `flatten` bug with `(into [] cat)` (correctness)
5. **ui.cljs**: Tighten destructuring in `::rate-card` (+clarity)
6. **ui.cljs**: Separate async from pure in `load-and-sync-cards!` (+clarity)
7. **ui.cljs**: Remove redundant `render!` calls (correctness, watch already handles it)
8. **core.cljc**: Remove unused `:log []` from `reduce-events` (cleanup)

### ❌ Reject (Not Worth It)

1. **core.cljc**: Replace loop/recur with reduce (less clear, has bug)
2. **core.cljc**: Use `medley/update-existing` in apply-event (hurts clarity)
3. **core.cljc**: Use `assoc-some` in card-with-meta (no benefit)
4. **fs.cljs**: Narrow error recovery (over-engineering)
5. **ui.cljs**: Extract `card->view` helper (doesn't reduce complexity)
6. **ui.cljs**: Create `apply-state!` wrapper (doesn't address root cause)
7. **Cross-cutting**: Add bang suffix to I/O functions (wrong convention)
8. **Cross-cutting**: Extract `current-date` helper (over-abstraction)
9. **Cross-cutting**: Shared card-type registry (YAGNI for 3 types)

### ⚠️ Maybe (Edge Cases)

1. **ui.cljs**: Dedupe parsed-cards with `distinct-by` (edge case protection, probably overkill)

---

## Total LOC Impact

**Before**: 579 LOC
**After**: ~567 LOC (net -12 LOC)

**Changes**:
- fs.cljs: -12 LOC (IDB helper extraction)
- core.cljc: -2 LOC (filter-vals, remove :log)
- ui.cljs: +2 LOC (restructuring changes are neutral)

**Net result**: Slightly cleaner code with 2 bug fixes (IDB write, render duplication) and modest clarity improvements.

---

## Recommendation

Apply the 8 "Clear Wins" only. The current codebase is already clean, and most suggestions are either over-abstraction or marginal improvements that don't justify the changes. The IDB helper extraction is the biggest win (DRY + correctness), followed by the render duplication fix.

**Priority order**:
1. Remove redundant `render!` calls (correctness bug)
2. Fix IndexedDB write completion (correctness bug)
3. Extract `idb-request->promise` helper (DRY)
4. Fix flatten bug (correctness)
5. Apply minor clarity improvements (destructuring, async separation)
6. Remove dead code (`:log []`)
7. Use `filter-vals` (idiomatic)
