# Anki Refactoring Analysis - Summary

**Date**: 2025-10-09
**Agents**: 4x GPT-5 Codex (reasoning-effort=high)
**Original LOC**: 533
**Target LOC**: ~470 (~12% reduction)

## Executive Summary

Four parallel Codex agents analyzed the Anki codebase focusing on simplification while maintaining functionality. Key findings: **~60 LOC can be removed** through data-driven patterns, component unification, and platform abstraction helpers.

## High-Value Refactorings (Recommended)

### 1. Time Handling Helpers (Agent 3)
**LOC Saved**: ~10
**Complexity**: Low
**Risk**: None

**Problem**: Repeated `#?(:clj ... :cljs ...)` conditionals for timestamps (4 occurrences).

**Solution**:
```clojure
;; Add helpers
(defn now-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn date-from-ms [ms]
  #?(:clj (java.util.Date. ms)
     :cljs (js/Date. ms)))

;; Usage
(let [now (now-ms)
      due-at (date-from-ms (+ now interval-ms))]
  ...)
```

**Files**: `src/lab/anki/core.cljc` (lines 37, 63, 106, 132)

---

### 2. Table-Driven Scheduler (Agent 3)
**LOC Saved**: ~5
**Complexity**: Low
**Risk**: None

**Problem**: `case` statement for rating intervals is verbose.

**Solution**:
```clojure
(def rating->interval-ms
  {:forgot 0 :hard 60000 :good 300000 :easy 600000})

(-> card-meta
    (update :reviews inc)
    (assoc :due-at (date-from-ms (+ (now-ms) (get rating->interval-ms rating 0)))
           :last-rating rating))
```

**Files**: `src/lab/anki/core.cljc:58-73`

---

### 3. Drop Unused Metadata Fields (Agent 3)
**LOC Saved**: ~3
**Complexity**: Low
**Risk**: None

**Problem**: `:interval` and `:ease-factor` are never read.

**Solution**:
```clojure
;; Remove from new-card-meta
{:card-hash hash
 :created-at (date-from-ms (now-ms))
 :due-at (date-from-ms (now-ms))
 :reviews 0}
;; Drop: :interval 0, :ease-factor 2.5
```

**Files**: `src/lab/anki/core.cljc:115-120`

---

### 4. Unified Card Parsing (Agent 2)
**LOC Saved**: ~15
**Complexity**: Medium
**Risk**: Low (tests will catch issues)

**Problem**: Separate `parse-qa-card` and `parse-cloze-card` functions duplicate "trim + tag with :type" logic.

**Solution**:
```clojure
(def qa-pattern #"^(.+?)\s*;\s*(.+)$")
(def cloze-pattern #"\[([^\]]+)\]")

(def card-parsers
  [{:type :qa
    :parse (fn [text]
             (when-let [[_ q a] (re-matches qa-pattern text)]
               {:question (str/trim q) :answer (str/trim a)}))}
   {:type :cloze
    :parse (fn [text]
             (when-let [matches (re-seq cloze-pattern text)]
               {:template text :deletions (mapv second matches)}))}])

(defn parse-card [text]
  (let [trimmed (str/trim text)]
    (some (fn [{:keys [type parse]}]
            (when-let [data (parse trimmed)]
              (assoc data :type type)))
          card-parsers)))
```

**Files**: `src/lab/anki/core.cljc:7-30`
**Benefits**: Adding new card types now requires only 1 map entry (no orchestration changes)

---

### 5. Unified Review Component (Agent 4)
**LOC Saved**: ~20
**Complexity**: Medium
**Risk**: Low

**Problem**: `review-card-qa` and `review-card-cloze` duplicate rating buttons and show/hide logic.

**Solution**:
```clojure
(def rating-options
  [[:forgot "Forgot"] [:hard "Hard"] [:good "Good"] [:easy "Easy"]])

(defn rating-buttons [on-rating]
  [:div {:class "rating-buttons"}
   (for [[value label] rating-options]
     ^{:key value}
     [:button {:on {:click #(on-rating value)}} label])])

(defn review-card [{:keys [card show-answer? on-show-answer on-rating]}]
  (let [{:keys [front back class]}
        (case (:type card)
          :qa {:front [:div.question [:h2 "Question"] [:p (:question card)]]
               :back [:div.answer [:h2 "Answer"] [:p (:answer card)]]
               :class "qa-card"}
          :cloze {:front [:div.cloze-text [:p (masked-template card show-answer?)]]
                  :back nil
                  :class "cloze-card"})]
    [:div {:class (str "review-card " class)}
     front
     (if show-answer?
       (list back (rating-buttons on-rating))
       [:button {:on {:click on-show-answer}} "Show Answer"])]))
```

**Files**: `src/lab/anki/ui.cljs:23-63`
**Benefits**: Single component, shared button labels, DRY rating logic

---

## Lower-Priority Refactorings (Optional)

### 6. Shared Rating Handler (Agent 4)
**LOC Saved**: ~8
**Complexity**: Low

Extract duplicated `p/let` pipeline from both QA and cloze branches in `review-screen`.

### 7. Generic FS Helpers (Agent 4)
**LOC Saved**: ~10
**Complexity**: Medium

Replace `read-edn-file`, `read-markdown-file`, `load-log`, `load-cards` with generic `read-file` helper taking `:parse` and `:default` params.

### 8. Data-Driven DOM Binding (Agent 4)
**LOC Saved**: ~5
**Complexity**: Low

Replace `when-let` + `addEventListener` boilerplate with `bind-click!` helper.

---

## NOT Recommended

### Merge `:meta` into `:cards` Map (Agent 1)
**LOC Saved**: ~15
**Complexity**: High
**Risk**: High

**Why Skip**: Increases nesting (`[:cards hash :meta]` vs `[:meta hash]`), makes queries harder, and breaks separation of concerns. The LOC savings aren't worth the mental overhead.

---

## Implementation Plan

1. **Phase 1: Helpers** (30 minutes)
   - Add `now-ms`, `date-from-ms` helpers
   - Add `rating->interval-ms` map
   - Drop unused metadata fields
   - Run `npm test` to verify

2. **Phase 2: Parsing** (45 minutes)
   - Implement `card-parsers` registry
   - Update `parse-card`
   - Remove old `parse-qa-card` and `parse-cloze-card`
   - Run `npm test` to verify

3. **Phase 3: UI** (60 minutes)
   - Extract `rating-buttons` component
   - Create unified `review-card`
   - Update `review-screen` to use new component
   - Run Playwright tests

4. **Phase 4: Cleanup** (optional, 30 minutes)
   - Shared rating handler
   - Generic FS helpers
   - DOM binding helper

**Total Estimated Time**: 2-3 hours
**Expected Result**: ~470 LOC (12% reduction), same functionality

---

## Testing Strategy

### Unit Tests (existing)
Run `npm test` after each phase to ensure core logic intact:
- `test/lab/anki/core_test.cljc`

### Playwright Tests (new)
Created in `test/playwright/anki.spec.js`:
- Card parsing (QA and cloze)
- Card hashing consistency
- Scheduling algorithm
- Event sourcing (create, review, reduce)
- Due cards query
- UI rendering

Run with: `npx playwright test`

---

## Files to Modify

| File | Changes | LOC Impact |
|------|---------|------------|
| `src/lab/anki/core.cljc` | Helpers, parsing, scheduling | -25 |
| `src/lab/anki/ui.cljs` | Unified component | -20 |
| `src/lab/anki/fs.cljs` | (optional) Generic helpers | -10 |

**Total**: -55 to -65 LOC (depends on optional refactors)

---

## Agent Reports

Full analysis available in:
- `agent-1-data-structures.md` - State shape, event sourcing, hashing
- `agent-2-parsing.md` - Card parsing, extensibility, regex
- `agent-3-scheduling.md` - Mock algorithm, time handling, metadata
- `agent-4-ui-fs.md` - UI components, FS operations, DOM wiring

---

## Next Steps

1. Review this summary with user
2. Apply Phase 1 (helpers) as a quick win
3. Run existing tests (`npm test`)
4. Apply Phase 2 (parsing) for extensibility
5. Run Playwright tests (`npx playwright test`)
6. Apply Phase 3 (UI) if approved
7. Verify final LOC count: `wc -l src/lab/anki/*.{cljc,cljs}`

**Expected outcome**: Cleaner, more maintainable codebase with **same functionality**, validated by comprehensive tests.
