# Replicant Testing Best Practices

> **Architecture Note (2025-11)**: Session state (selection, editing, folding) now lives in a
> separate atom, not in DB nodes. Components receive session-derived props. Tests should pass
> session state as props rather than embedding in DB. See `shell/session.cljs` for session shape.

## Philosophy

**Replicant's superpower**: Because UI is pure data (hiccup), you can test everything without a browser:
1. Call view functions with state
2. Inspect the resulting hiccup (it's just data!)
3. Extract actions from event handlers
4. Test action handlers as pure functions
5. Verify state updates and effects

## Testing Levels

### 1. View Testing (Unit)

Test that views render correctly for different states.

```clojure
(deftest block-view-test
  (testing "Block renders with correct text"
    (let [db {:nodes {"a" {:type :block :props {:text "Hello world"}}}}
          hiccup (Block {:db db :block-id "a" :on-intent identity})]
      ;; Assert hiccup structure
      (is (= "Hello world"
             (get-in hiccup [3 :props :text]))
          "Block displays correct text")))

  (testing "Editing mode shows contenteditable"
    (let [db {:nodes {"a" {:type :block :props {:text "Edit me"}}
                      "session/ui" {:props {:editing-block-id "a"}}}]
          hiccup (Block {:db db :block-id "a" :on-intent identity})]
      (is (get-in hiccup [3 :contentEditable])
          "Editing mode renders contenteditable"))))
```

### 2. Action Extraction Testing

Extract data-driven actions from hiccup and test them.

```clojure
(ns test-util
  (:require [clojure.walk :as walk]))

(defn select-actions
  "Extract actions from event handler at path in hiccup."
  [hiccup selector event-path]
  ;; Find element matching selector
  ;; Navigate to [:on event-path]
  ;; Return action vector(s)
  ...)

;; Example usage:
(deftest block-editing-actions-test
  (let [db {:nodes {"a" {:type :block :props {:text "Test"}}}}
        hiccup (Block {:db db :block-id "a" :on-intent identity})
        ;; Extract actions from :input event handler
        input-actions (select-actions hiccup :.content-edit [:on :input])]
    (is (= [[:update-content "a" :event/target.value]]
           input-actions)
        "Input event dispatches update-content action")))
```

### 3. Action Handler Testing

Test that action handlers correctly update state and produce effects.

```clojure
(deftest update-content-action-test
  (testing "Update content produces correct operations"
    (let [db {:nodes {"a" {:type :block :props {:text "Old"}}}}
          result (intent/apply-intent db {:type :update-content
                                          :block-id "a"
                                          :text "New"})
          ops (:ops result)]
      (is (= [{:op :update-node :id "a" :props {:text "New"}}]
             ops)
          "Produces correct update operation"))))
```

### 4. Integration Testing

Test full user interactions: render → extract actions → apply actions → re-render.

```clojure
(deftest typing-in-block-integration-test
  (let [db (-> (db/empty-db)
               (tx/interpret [{:op :create-node :id "a" :type :block :props {:text ""}}
                              {:op :place :id "a" :under :doc :at :last}])
               :db)

        ;; 1. Render initial view
        hiccup-1 (Block {:db db :block-id "a" :on-intent identity})

        ;; 2. Extract input action
        input-actions (select-actions hiccup-1 :.content-edit [:on :input])

        ;; 3. Simulate typing "Hello" (replace :event/target.value placeholder)
        concrete-actions (walk/postwalk
                          #(if (= % :event/target.value) "Hello" %)
                          input-actions)

        ;; 4. Apply actions
        {:keys [ops]} (intent/apply-intent db (first concrete-actions))
        db-2 (:db (tx/interpret db ops))

        ;; 5. Verify new state
        hiccup-2 (Block {:db db-2 :block-id "a" :on-intent identity})]

    (is (= "Hello" (get-in db-2 [:nodes "a" :props :text]))
        "Text updated in db")
    (is (= "Hello" (extract-text-from-hiccup hiccup-2))
        "View reflects new text")))
```

## Current State Analysis

### ✅ What We're Doing Well

1. **Kernel Testing** - Excellent coverage of transaction pipeline, operations, schema validation
2. **Plugin Testing** - Testing intent handlers in isolation with pure functions
3. **Property-Based Testing** - Using test.check to generate random operations
4. **E2E Testing** - Playwright tests for actual browser behavior

### ❌ What's Missing

1. **No View Tests** - We don't test that components render correct hiccup
2. **No Action Extraction** - Not testing that event handlers dispatch correct actions
3. **No Integration Tests** - Not testing full render → action → update → re-render cycle
4. **Limited REPL Testing** - Could leverage REPL more for component testing

### 🎯 Recommended Improvements

#### 1. Add View Test Utilities

Create `test/view_util.cljc`:

```clojure
(ns view-util
  "Utilities for testing Replicant views."
  (:require [clojure.walk :as walk]))

(defn find-element
  "Find first element in hiccup matching selector.
   Selector can be tag keyword or class/id string."
  [hiccup selector]
  (let [matches? (fn [el]
                   (cond
                     (keyword? selector)
                     (= (first el) selector)

                     (string? selector)
                     (let [attrs (when (map? (second el)) (second el))]
                       (or (= (:id attrs) selector)
                           (some #{selector} (:class attrs))))))]
    (walk/prewalk
     (fn [x]
       (when (and (vector? x) (matches? x))
         (reduced x)))
     hiccup)))

(defn select-actions
  "Extract actions from event handler in hiccup element."
  [hiccup selector event-path]
  (let [el (find-element hiccup selector)
        attrs (when (map? (second el)) (second el))]
    (get-in attrs (cons :on event-path))))

(defn select-attribute
  "Extract attribute value from element."
  [hiccup selector attr-path]
  (let [el (find-element hiccup selector)
        attrs (when (map? (second el)) (second el))]
    (get-in attrs attr-path)))

(defn has-class?
  "Check if element has CSS class."
  [hiccup selector class-name]
  (let [classes (select-attribute hiccup selector :class)]
    (some #{class-name} classes)))

(defn interpolate-placeholders
  "Replace event placeholders with actual values for testing."
  [actions values]
  (walk/postwalk
   (fn [x]
     (if (contains? values x)
       (get values x)
       x))
   actions))
```

#### 2. Add Component Tests

Create `test/components/block_test.cljc`:

```clojure
(ns components.block-test
  (:require [clojure.test :refer [deftest testing is]]
            [view-util :as vu]
            [components.block :as block]
            [kernel.db :as db]))

(deftest block-view-rendering-test
  (testing "Basic rendering"
    (let [db {:nodes {"a" {:type :block :props {:text "Hello"}}}}
          view (block/Block {:db db :block-id "a" :on-intent identity})]
      (is (vector? view) "Returns hiccup vector")
      (is (= "Hello" (vu/select-attribute view :.content-view :text))
          "Displays block text")))

  (testing "Editing mode"
    (let [db {:nodes {"a" {:type :block :props {:text "Edit"}}
                      "session/ui" {:props {:editing-block-id "a"}}}]
          view (block/Block {:db db :block-id "a" :on-intent identity})]
      (is (vu/select-attribute view :.content-edit :contentEditable)
          "Shows contenteditable in edit mode")
      (is (not (vu/find-element view :.content-view))
          "Hides view mode")))

  (testing "CSS classes"
    (let [db {:nodes {"a" {:type :block :props {:text "Folded"}}
                      "session/ui" {:props {:folded #{"a"}}}}}
          view (block/Block {:db db :block-id "a" :on-intent identity})]
      (is (vu/has-class? view :.block "folded")
          "Applies folded class"))))

(deftest block-event-handlers-test
  (testing "Input event dispatches update-content"
    (let [db {:nodes {"a" {:type :block :props {:text "Old"}}
                      "session/ui" {:props {:editing-block-id "a"}}}]
          view (block/Block {:db db :block-id "a" :on-intent identity})
          actions (vu/select-actions view :.content-edit [:input])]
      (is (= [[:update-content "a" :event/target.value]]
             actions)
          "Extracts correct action template")))

  (testing "Arrow keys dispatch navigation"
    (let [db {:nodes {"a" {:type :block :props {:text "Nav"}}}}
          view (block/Block {:db db :block-id "a" :on-intent identity})
          actions (vu/select-actions view :.content-edit [:keydown])]
      ;; Verify navigation action exists
      (is (some #(= :navigate-with-cursor-memory (:type %))
                actions)
          "Includes navigation action"))))
```

#### 3. Add Intent Integration Tests

Create `test/integration/component_intents_test.cljc`:

```clojure
(ns integration.component-intents-test
  "Test full flow: component → action → intent → ops → updated component"
  (:require [clojure.test :refer [deftest testing is]]
            [view-util :as vu]
            [components.block :as block]
            [kernel.db :as db]
            [kernel.intent :as intent]
            [kernel.transaction :as tx]))

(deftest typing-updates-block-test
  (let [;; 1. Setup initial state
        db (-> (db/empty-db)
               (tx/interpret [{:op :create-node :id "a" :type :block :props {:text ""}}
                              {:op :place :id "a" :under :doc :at :last}
                              {:op :update-node :id "session/ui"
                               :props {:editing-block-id "a"}}])
               :db)

        ;; 2. Render component
        view-1 (block/Block {:db db :block-id "a" :on-intent identity})

        ;; 3. Extract and execute action
        input-action (vu/select-actions view-1 :.content-edit [:input])
        concrete-action (vu/interpolate-placeholders
                         input-action
                         {:event/target.value "Hello world"})

        ;; 4. Apply intent
        {:keys [ops]} (intent/apply-intent db concrete-action)
        db-2 (:db (tx/interpret db ops))

        ;; 5. Re-render with new state
        view-2 (block/Block {:db db-2 :block-id "a" :on-intent identity})]

    ;; Verify db updated
    (is (= "Hello world" (get-in db-2 [:nodes "a" :props :text])))

    ;; Verify view reflects change
    (is (= "Hello world" (vu/select-attribute view-2 :.content-edit :text)))))
```

## Testing Data-Driven Events

### Why Data-Driven Events Are Testable

```clojure
;; Function handler - HARD to test
[:button {:on {:click (fn [e]
                        (swap! !state assoc :clicked true)
                        (.log js/console "Clicked"))}}]

;; Data handler - EASY to test
[:button {:on {:click [[:set-clicked true]
                       [:log "Clicked"]]}}]
```

With data handlers:
1. **Extract actions** from hiccup (it's just data!)
2. **Test action handler** as pure function
3. **Verify effects** separately
4. **No mocking** required

### Example: Testing Data-Driven Events

```clojure
(deftest button-click-action-test
  ;; 1. Render view
  (let [view (my-button-view {:state :initial})

        ;; 2. Extract actions (no DOM needed!)
        click-actions (vu/select-actions view :button [:on :click])]

    ;; 3. Verify actions
    (is (= [[:set-clicked true]
            [:log "Clicked"]]
           click-actions))

    ;; 4. Test action handler
    (let [{:keys [new-state effects]}
          (handle-actions {:state :initial} {} click-actions)]

      ;; 5. Verify state update
      (is (= true (:clicked new-state)))

      ;; 6. Verify effects
      (is (= [[:log "Clicked"]] effects)))))
```

## REPL-Driven Testing Workflow

### Quick Component REPL Tests

```clojure
;; In REPL:
(require '[components.block :as block]
         '[kernel.db :as db]
         '[clojure.pprint :refer [pprint]])

;; Create test db
(def test-db
  {:nodes {"a" {:type :block :props {:text "Test"}}}})

;; Render component
(def view (block/Block {:db test-db :block-id "a" :on-intent prn}))

;; Inspect hiccup
(pprint view)

;; Extract actions
(require '[view-util :as vu])
(vu/select-actions view :.content-edit [:on :input])
;; => [[:update-content "a" :event/target.value]]

;; Test action
(require '[kernel.intent :as intent])
(intent/apply-intent test-db {:type :update-content
                               :block-id "a"
                               :text "Updated"})
;; => {:ops [{:op :update-node :id "a" :props {:text "Updated"}}]}
```

### REPL-Driven Development Cycle

1. **Write view function** (pure function, returns hiccup)
2. **Test in REPL** with sample data
3. **Inspect hiccup** structure, verify correct
4. **Extract actions** from event handlers
5. **Test actions** in isolation
6. **Write automated tests** for critical paths
7. **E2E tests** for integration validation

## Comparison: Our Approach vs replicant-todomvc

### replicant-todomvc (Reference Implementation)

**View Tests**:
```clojure
;; Test view rendering
(let [view (item-view state 0 item)]
  (is (= "Test Item" (-> (select :label view) first second))))

;; Test action extraction
(let [on-click (select-actions :button.destroy [:on :click] view)
      result (handle-actions state {} on-click)]
  (is (empty? (:new-state result :app/todo-items))))
```

**Action Tests**:
```clojure
(deftest handle-action-assoc
  (is (= {:new-state {:foo :bar}}
         (handle-action {} {} [:db/assoc :foo :bar]))))
```

**Integration Tests**:
- Render view → extract actions → handle actions → verify state
- All without browser (runs in Node.js in milliseconds)

### Our Approach (Current)

**Kernel Tests**: ✅ Excellent
```clojure
(deftest test-create-node
  (let [db (apply-ops [(create-op "a" :div)])]
    (is (node-exists? db "a"))))
```

**Plugin Tests**: ✅ Good
```clojure
(deftest delete-forward-middle-test
  (let [{:keys [ops]} (intent/apply-intent db {:type :delete-forward ...})]
    (is (= "Helo" (get-in db' [:nodes "a" :props :text])))))
```

**View Tests**: ❌ Missing
```clojure
;; WE DON'T HAVE THESE:
;; - Test Block component renders correct hiccup
;; - Test event handlers dispatch correct actions
;; - Test action extraction from hiccup
```

**Integration Tests**: ⚠️ Only E2E
- E2E tests with Playwright (good but slow)
- No fast unit-level integration tests

## Benefits of Adding View Tests

1. **Fast Feedback** - Test views in milliseconds, not seconds
2. **No Browser Required** - Run in REPL or CI without Playwright
3. **Precise Assertions** - Test exact hiccup structure, not DOM queries
4. **Catch Bugs Early** - Before E2E tests, before manual testing
5. **Living Documentation** - View tests show how components work
6. **Refactor Confidence** - Change internals, tests verify behavior
7. **REPL-Driven Dev** - Interactive development without browser

## Action Items

### High Priority

1. **Create view test utilities** (`test/view_util.cljc`)
2. **Add Block component tests** - Most critical component
3. **Document testing patterns** - In REPLICANT_TESTING.md
4. **Update REPL helpers** - Add component testing functions

### Medium Priority

5. **Test other components** - Sidebar, BlockRef, PageRef, etc.
6. **Add integration tests** - Render → action → update cycles
7. **Property-based view tests** - Random state → render → verify invariants

### Low Priority

8. **Visual regression tests** - Already using Percy
9. **Performance benchmarks** - Render performance for large trees
10. **Fuzz testing** - Random hiccup generation

## Resources

- **replicant-todomvc**: `~/Projects/best/replicant-todomvc/test/`
- **Replicant docs**: https://replicant.fun/
- **Our kernel tests**: `test/core_transaction_test.cljc` (excellent examples)
- **Our plugin tests**: `test/plugins/editing_test.cljc` (good patterns)

## Summary

**Key Insight**: Replicant's data-driven approach makes UI testing trivial:
- Views are pure functions (state → hiccup)
- Actions are data (extractable from hiccup)
- Action handlers are pure (state + action → new state + effects)

**We're missing**: The middle layer (view tests + action extraction).

**Solution**: Add lightweight view testing utilities and tests for critical components. Start with Block component, expand from there.
