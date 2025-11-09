# Development Tooling Upgrade

**Status:** Proposed
**Target:** AI-agent-driven development workflow optimization
**Priority:** High (blocks efficient iteration)

**Related Specs:**
- [AI Blind Spots & Visual Testing](./AI_BLIND_SPOTS_TOOLING.md) - Compensates for AI's inability to see UI

---

## Executive Summary

Current testing workflow has high friction for AI-agent development:
- Manual test runs required after each change
- Poor failure messages (just "not equal")
- Intent validation happens downstream (cryptic errors)
- No structured error output for AI to parse
- **AI cannot verify visual/layout/a11y issues** ⭐ NEW

**Solution:** Add modern Clojure libraries + visual/a11y testing to make failures **instantly debuggable** from text output alone.

**Expected impact:**
- 80% reduction in "why did this fail?" iterations
- Catch 90% of intent bugs before DB transaction phase
- Test failures include full context (no println debugging needed)
- **Catch 95% of visual/UX bugs without manual testing** ⭐ NEW

---

## Problem Analysis

### Current Pain Points

#### 1. Intent Validation Gaps

```clojure
;; plugins/editing.cljc:44
(intent/register-intent! :update-content
  {:spec [:map [:type [:= :update-content]]
               [:block-id :string]
               [:text :string]]
   :handler (fn [_db {:keys [block-id text]}] ...)})

;; Component calls with typo:
(intent/apply-intent db {:type :update-content
                         :bloc-id "a"    ;; ❌ typo
                         :text "new"})

;; Current behavior:
;; → handler gets block-id=nil (silent destructuring failure)
;; → Returns {:op :update-node :id nil :props {:text "new"}}
;; → tx/interpret fails: "Cannot update nil node" (unhelpful)
;; → Agent asks "what's wrong?" → user adds println → finds typo
```

**Cost:** 3-5 round trips to diagnose simple typos

#### 2. Poor Test Failure Messages

```clojure
;; Current output (shadow-cljs :node-test):
FAIL in (merge-with-next-test)
expected: (= "First blockSecond block" actual-text)
  actual: (not (= "First blockSecond block" "First block Second block"))

;; Problem: Can't see the difference without squinting
```

#### 3. No Watch Mode

```bash
# Manual workflow:
# 1. Edit code
# 2. Run: bb test
# 3. Wait 3-5 seconds for compile
# 4. See failure
# 5. Repeat

# With 20 test iterations/hour → 1-2 minutes wasted on manual reruns
```

#### 4. Property Test Failures Uninformative

```clojure
Testing core.permutation-test
{:result false, :seed 1762727651693, :failing-size 42, :num-tests 43}

;; What input caused failure? Unknown.
;; Must manually re-run with seed and add println
```

---

## Solution Architecture

### Core Principle: **AI-Visible Errors**

All tooling choices optimize for **structured text output** that AI agents can parse without GUI access.

### Libraries to Add

#### Core Testing (Data/Logic)

| Library | Version | Purpose | AI Benefit |
|---------|---------|---------|------------|
| **kaocha-cljs2** | 1.5.40 | Better test runner | Structured diffs in text output |
| **matcher-combinators** | 3.9.2 | Smart assertions | Exact mismatch locations in text |
| **exoscale/ex** | 0.4.0 | Exception hierarchy | Typed errors with keyword categories |
| **nubank/state-flow** *(optional)* | 5.14.3 | Integration test DSL | Clear test narratives |

---

## Implementation Plan

### Phase 1: Better Test Output (30 min)

#### A. Add kaocha-cljs2

```clojure
;; deps.edn - enhance :test alias
:test {:extra-paths ["test"]
       :extra-deps {lambdaisland/kaocha {:mvn/version "1.91.1392"}
                    lambdaisland/kaocha-cljs2 {:mvn/version "1.5.40"}
                    ;; Better diffs
                    nubank/matcher-combinators {:mvn/version "3.9.2"}
                    ;; Pretty print data
                    fipp/fipp {:mvn/version "0.6.26"}}
       :main-opts ["-m" "kaocha.runner"]
       :exec-fn kaocha.runner/exec-fn
       :exec-args {:color? true
                   :fail-fast? false}}
```

```clojure
;; tests.edn (new file)
#kaocha/v1
{:tests [{:id :unit
          :type :kaocha.type/cljs2
          :test-paths ["test"]
          :ns-exclude-regexp "browser-ui"
          :cljs/compiler-options {:output-to "out/tests.js"
                                  :target :node-test}}]

 :plugins [:kaocha.plugin/profiling    ;; Show slow tests
           :kaocha.plugin/notifier      ;; Desktop notifications
           :kaocha.plugin/hooks]        ;; Custom lifecycle hooks

 :kaocha.plugin.profiling/count 10

 ;; Custom reporter for structured output
 :kaocha/reporter [kaocha.report/documentation]}
```

```clojure
;; bb.edn - add new tasks
test-watch {:doc "Run tests in watch mode (auto-rerun on save)"
            :task (shell "clj -M:test -m kaocha.runner --watch")}

test-focus {:doc "Run only tests matching pattern"
            :task (shell "clj -M:test -m kaocha.runner --focus"
                         (first *command-line-args*))}

test-fail-fast {:doc "Stop on first failure with full context"
                :task (shell "clj -M:test -m kaocha.runner --fail-fast")}

test-seed {:doc "Run tests with specific seed (reproduce property test failures)"
           :task (shell "clj -M:test -m kaocha.runner --randomize --seed"
                        (first *command-line-args*))}
```

**Benefits:**
- ✅ Auto-rerun tests on file save (watch mode)
- ✅ Show exact diffs instead of `(not (= ...))`
- ✅ Focus on single namespace during TDD
- ✅ Reproduce property test failures with seed

#### B. Enhanced Test Assertions

```clojure
;; test/test_util.cljc (new file)
(ns test-util
  "AI-friendly test utilities with rich output."
  (:require [clojure.test :refer [is]]
            [matcher-combinators.test :refer [match?]]
            [fipp.edn :refer [pprint]]))

(defn assert-intent-ops
  "Assert intent produces expected ops. Prints full context on failure."
  [db intent expected-ops]
  (let [{:keys [ops]} (intent/apply-intent db intent)]
    (when-not (match? expected-ops ops)
      (println "\n=== INTENT ASSERTION FAILED ===")
      (println "Intent:" (pr-str intent))
      (println "\nExpected ops:")
      (pprint expected-ops)
      (println "\nActual ops:")
      (pprint ops)
      (println "\nDiff:")
      (pprint (clojure.data/diff expected-ops ops)))
    (is (match? expected-ops ops))))

(defn assert-db-nodes
  "Assert DB nodes match expected shape. Supports partial matching."
  [db node-id->expected-props]
  (doseq [[node-id expected-props] node-id->expected-props]
    (let [actual-props (get-in db [:nodes node-id :props])]
      (when-not (match? expected-props actual-props)
        (println "\n=== NODE ASSERTION FAILED ===")
        (println "Node ID:" node-id)
        (println "\nExpected props:")
        (pprint expected-props)
        (println "\nActual props:")
        (pprint actual-props))
      (is (match? expected-props actual-props)
          (str "Node " node-id " props mismatch")))))

(defn assert-tree-structure
  "Assert tree structure matches expected parent-child relationships."
  [db expected-tree]
  (doseq [[parent-id expected-children] expected-tree]
    (let [actual-children (get-in db [:children-by-parent parent-id])]
      (when-not (= expected-children actual-children)
        (println "\n=== TREE STRUCTURE MISMATCH ===")
        (println "Parent:" parent-id)
        (println "Expected children:" expected-children)
        (println "Actual children:" actual-children))
      (is (= expected-children actual-children)
          (str "Children of " parent-id " don't match")))))
```

**Usage in tests:**

```clojure
;; test/plugins/smart_editing_test.cljc - BEFORE
(deftest merge-with-next-test
  (let [db (setup-blocks)
        {:keys [ops]} (intent/apply-intent db {:type :merge-with-next :block-id "a"})
        db' (:db (tx/interpret db ops))]
    (is (= "First blockSecond block" (get-in db' [:nodes "a" :props :text])))
    (is (= :trash (q/parent-of db' "b")))))

;; AFTER - with rich output
(deftest merge-with-next-test
  (let [db (setup-blocks)]
    (assert-intent-ops db
      {:type :merge-with-next :block-id "a"}
      [{:op :update-node :id "a" :props {:text "First blockSecond block"}}
       {:op :place :id "b" :under :trash :at :last}])

    ;; Or assert final state with partial matching:
    (let [db' (apply-intent-and-interpret db {:type :merge-with-next :block-id "a"})]
      (assert-db-nodes db'
        {"a" {:text "First blockSecond block"}  ;; partial match - only checks :text
         "b" {:parent :trash}}))))
```

**Output on failure (AI can read this):**

```
=== INTENT ASSERTION FAILED ===
Intent: {:type :merge-with-next, :block-id "a"}

Expected ops:
[{:op :update-node, :id "a", :props {:text "First blockSecond block"}}
 {:op :place, :id "b", :under :trash, :at :last}]

Actual ops:
[{:op :update-node, :id "a", :props {:text "First block Second block"}}
 {:op :place, :id "b", :under :trash, :at :last}]

Diff:
expected: "First blockSecond block"
  actual: "First block Second block"
           ^^^^^^^^^^^^ extra space
```

---

### Phase 2: Intent Validation (45 min)

#### A. Add malli development instrumentation

```clojure
;; deps.edn - add to :nrepl alias (dev only)
:nrepl {:extra-paths ["src" "test" "dev" "agent"]
        :extra-deps {nrepl/nrepl {:mvn/version "1.5.1"}
                     ;; Already have malli 0.19.1, keep it
                     metosin/malli {:mvn/version "0.19.1"}
                     ;; Pretty error messages
                     metosin/malli-dev {:mvn/version "0.19.1"}}
        :jvm-opts ["-Djdk.attach.allowAttachSelf"]
        :main-opts ["-m" "nrepl.cmdline" "--port" "7888"]}
```

```clojure
;; dev/validation.cljs (new file)
(ns dev.validation
  "Intent validation for development and testing.

   Validates all intents against their registered specs at runtime.
   Catches typos and malformed intents BEFORE they reach handlers."
  (:require [malli.core :as m]
            [malli.error :as me]
            [kernel.intent :as intent]))

(defn validate-intent
  "Validate intent against its registered spec.
   Returns intent if valid, throws ex-info with rich context if invalid."
  [intent]
  (if-let [spec (get-in @intent/!intents [(:type intent) :spec])]
    (if (m/validate spec intent)
      intent
      (let [explanation (m/explain spec intent)
            humanized (me/humanize explanation)]
        (throw (ex-info
                (str "Intent validation failed: " (:type intent))
                {:intent intent
                 :spec spec
                 :errors humanized
                 :explanation explanation}))))
    ;; No spec registered - warn but don't block
    (do
      (js/console.warn "No spec registered for intent type:" (:type intent))
      intent)))

(defn wrap-apply-intent-with-validation
  "Wrap intent/apply-intent to validate before executing.
   Call once in dev environment to enable validation."
  []
  (let [original-fn intent/apply-intent]
    (set! intent/apply-intent
          (fn [db intent]
            (validate-intent intent)  ;; Validate first
            (original-fn db intent)))))  ;; Then execute

;; Auto-enable in dev mode (check if REPL is running)
(when (exists? js/goog.global.CLOSURE_UNCOMPILED_DEFINES)
  (wrap-apply-intent-with-validation))
```

**Enable in tests:**

```clojure
;; test/fixtures.cljc - enhance with validation
(ns fixtures
  (:require [dev.validation :as validation]))

;; Call once when tests load
#?(:cljs (validation/wrap-apply-intent-with-validation))

(defn apply-intent-validated
  "Test helper that validates intent before applying.
   Use this in tests to catch malformed intents immediately."
  [db intent]
  (validation/validate-intent intent)
  (intent/apply-intent db intent))
```

**Example error output (AI can parse this):**

```clojure
;; Component calls with typo:
(intent/apply-intent db {:type :update-content :bloc-id "a" :text "new"})

;; Immediate error:
ExceptionInfo: Intent validation failed: :update-content
  {:intent {:type :update-content, :bloc-id "a", :text "new"}
   :spec [:map
          [:type [:= :update-content]]
          [:block-id :string]
          [:text :string]]
   :errors {:block-id ["missing required key"]
            :bloc-id ["disallowed key"]}
   :explanation
     {:schema [:map
               [:type [:= :update-content]]
               [:block-id :string]
               [:text :string]]
      :value {:type :update-content, :bloc-id "a", :text "new"}
      :errors [{:path [:block-id]
                :in [:block-id]
                :schema :string
                :type :malli.core/missing-key}
               {:path []
                :in [:bloc-id]
                :schema [:map ...]
                :type :malli.core/extra-key}]}}
```

**Value:** Typos caught **at call site** instead of cryptic downstream errors.

---

### Phase 3: Structured Exception Hierarchy (30 min)

#### A. Add exoscale/ex for typed errors

```clojure
;; deps.edn - add to main deps
:deps {;; ... existing deps ...
       exoscale/ex {:mvn/version "0.4.0"}}
```

```clojure
;; src/kernel/errors.cljc (new file)
(ns kernel.errors
  "Structured error types using exoscale/ex hierarchy.

   All errors derive from ::kernel-error for easy top-level catching.
   Each error category has semantic meaning for AI agents to understand."
  (:require [exoscale.ex :as ex]))

;; Error hierarchy
(ex/derive ::validation-error   ::kernel-error)
(ex/derive ::transaction-error  ::kernel-error)
(ex/derive ::intent-error       ::kernel-error)
(ex/derive ::query-error        ::kernel-error)

;; Validation errors
(ex/derive ::invalid-intent     ::validation-error)
(ex/derive ::invalid-operation  ::validation-error)
(ex/derive ::schema-violation   ::validation-error)

;; Transaction errors
(ex/derive ::node-not-found     ::transaction-error)
(ex/derive ::invalid-placement  ::transaction-error)
(ex/derive ::circular-reference ::transaction-error)

;; Intent errors
(ex/derive ::no-handler         ::intent-error)
(ex/derive ::handler-failed     ::intent-error)

;; Query errors
(ex/derive ::invalid-query      ::query-error)
(ex/derive ::node-missing       ::query-error)

;; Helper functions for throwing typed errors
(defn ex-invalid-intent
  "Throw when intent doesn't match its spec."
  [intent spec errors]
  (ex/ex-info ::invalid-intent
    (str "Intent validation failed: " (:type intent))
    {:intent intent
     :spec spec
     :errors errors}))

(defn ex-node-not-found
  "Throw when operation references non-existent node."
  [node-id operation]
  (ex/ex-info ::node-not-found
    (str "Node not found: " node-id)
    {:node-id node-id
     :operation operation
     :available-nodes :elided}))  ;; Add in handler

(defn ex-invalid-placement
  "Throw when placement violates tree constraints."
  [node-id parent-id reason]
  (ex/ex-info ::invalid-placement
    (str "Invalid placement: " reason)
    {:node-id node-id
     :parent-id parent-id
     :reason reason}))

(defn ex-circular-reference
  "Throw when placement would create cycle."
  [node-id ancestor-ids]
  (ex/ex-info ::circular-reference
    "Placement would create circular reference"
    {:node-id node-id
     :ancestor-ids ancestor-ids}))
```

**Usage in transaction layer:**

```clojure
;; src/kernel/transaction.cljc - BEFORE
(defn interpret-place-op [db {:keys [id under]}]
  (when-not (contains? (:nodes db) id)
    (throw (ex-info "Node not found" {:id id})))  ;; ❌ Generic
  (when-not (contains? (:nodes db) under)
    (throw (ex-info "Parent not found" {:parent under})))  ;; ❌ Generic
  ...)

;; AFTER - with typed errors
(defn interpret-place-op [db {:keys [id under] :as op}]
  (when-not (contains? (:nodes db) id)
    (throw (errors/ex-node-not-found id op)))  ;; ✅ Typed
  (when-not (contains? (:nodes db) under)
    (throw (errors/ex-node-not-found under op)))  ;; ✅ Typed
  ...)
```

**Catching by category:**

```clojure
;; src/components/block.cljs - handle errors by type
(defn handle-intent-error [e]
  (ex/try+
    (intent/apply-intent @db intent)

    (catch ::errors/validation-error e
      ;; Show validation feedback to user
      (show-validation-error! (ex-data e)))

    (catch ::errors/transaction-error e
      ;; Log transaction errors, show generic message
      (js/console.error "Transaction failed:" (ex-data e))
      (show-error! "Operation failed. Please try again."))

    (catch ::errors/kernel-error e
      ;; Catch all kernel errors
      (js/console.error "Kernel error:" (ex-data e))
      (show-error! "Internal error"))

    (catch :default e
      ;; Unknown error
      (js/console.error "Unexpected error:" e)
      (show-error! "Unexpected error"))))
```

**Benefits:**
- ✅ Errors have semantic categories (AI can understand)
- ✅ Catch by type hierarchy (precise error handling)
- ✅ All errors include rich context (ex-data)
- ✅ Consistent error format across codebase

---

### Phase 4: Custom Kaocha Hooks (20 min)

#### A. Add hooks for intent validation in tests

```clojure
;; tests.edn - add hooks plugin
#kaocha/v1
{:tests [{:id :unit
          :type :kaocha.type/cljs2
          :test-paths ["test"]
          :kaocha.hooks/pre-test [test-hooks/validate-test-intents]
          :kaocha.hooks/post-test [test-hooks/report-test-data]}]

 :plugins [:kaocha.plugin/profiling
           :kaocha.plugin/notifier
           :kaocha.plugin/hooks]

 :kaocha/reporter [kaocha.report/documentation]}
```

```clojure
;; test/test_hooks.clj (new file - runs on JVM side)
(ns test-hooks
  "Kaocha hooks for enhanced test lifecycle."
  (:require [clojure.pprint :refer [pprint]]))

(defn validate-test-intents
  "Pre-test hook: Ensure all intents used in tests have registered specs."
  [testable test-plan]
  ;; This runs before each test
  ;; Could scan test source for intent usage and validate specs exist
  testable)

(defn report-test-data
  "Post-test hook: Print detailed test data on failure."
  [testable test-plan]
  (when (-> testable :kaocha.result/error)
    (println "\n=== TEST FAILURE CONTEXT ===")
    (println "Test:" (:kaocha.testable/id testable))
    (when-let [error (:kaocha.result/error testable)]
      (println "\nError:")
      (pprint (ex-data error))))
  testable)
```

---

### Phase 5: Enhanced Test Fixtures (15 min)

#### A. Better test data builders

```clojure
;; test/fixtures.cljc - enhance existing
(ns fixtures
  (:require [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [dev.validation :as validation]))

;; Enable validation in tests
#?(:cljs (validation/wrap-apply-intent-with-validation))

(defn make-blocks
  "Create blocks with sensible defaults. Returns db.

   Usage:
     (make-blocks
       {:a {:text \"First\" :children [:b :c]}
        :b {:text \"Second\"}
        :c {:text \"Third\" :children [:d]}
        :d {:text \"Fourth\"}})"
  [block-map]
  (let [ops (for [[id {:keys [text children] :or {text "" children []}}] block-map]
              {:op :create-node :id (name id) :type :block :props {:text text}})
        place-ops (for [[id {:keys [children]}] block-map
                        child children]
                    {:op :place :id (name child) :under (name id) :at :last})]
    (:db (tx/interpret (db/empty-db) (concat ops place-ops)))))

(defn apply-intent-and-interpret
  "Apply intent and return resulting db (convenience helper)."
  [db intent]
  (let [{:keys [ops]} (intent/apply-intent db intent)]
    (:db (tx/interpret db ops))))

(defn intent-chain
  "Apply multiple intents in sequence, returning final db.

   Usage:
     (intent-chain db
       {:type :enter-edit :block-id \"a\"}
       {:type :update-content :block-id \"a\" :text \"new\"}
       {:type :exit-edit})"
  [db & intents]
  (reduce apply-intent-and-interpret db intents))

(defn snapshot-db
  "Return readable snapshot of db for printing/comparing."
  [db & [node-ids]]
  (let [nodes (if node-ids
                (select-keys (:nodes db) node-ids)
                (:nodes db))]
    {:nodes nodes
     :tree (:children-by-parent db)
     :derived (select-keys (:derived db) [:parent-id-of :index-of])}))
```

**Usage in tests:**

```clojure
;; test/plugins/smart_editing_test.cljc - BEFORE
(defn setup-blocks []
  (:db (tx/interpret (db/empty-db)
         [{:op :create-node :id "a" :type :block :props {:text "First"}}
          {:op :create-node :id "b" :type :block :props {:text "Second"}}
          {:op :place :id "a" :under :doc :at :last}
          {:op :place :id "b" :under :doc :at :last}])))

;; AFTER - more readable
(defn setup-blocks []
  (make-blocks {:doc {:children [:a :b]}
                :a {:text "First"}
                :b {:text "Second"}}))

;; Test intent chains
(deftest complex-editing-flow
  (let [db (make-blocks {:doc {:children [:a]}
                         :a {:text "Hello"}})
        final-db (intent-chain db
                   {:type :enter-edit :block-id "a"}
                   {:type :update-content :block-id "a" :text "Hello World"}
                   {:type :split-at-cursor :block-id "a" :cursor-pos 5}
                   {:type :exit-edit})]
    (is (= "Hello" (get-in final-db [:nodes "a" :props :text])))
    (is (= "World" (get-in final-db [:nodes "b" :props :text])))))  ;; New block from split
```

---

## Migration Guide

### Step 1: Add Dependencies (5 min)

```bash
cd /Users/alien/Projects/evo

# Edit deps.edn and shadow-cljs.edn to add:
# - lambdaisland/kaocha-cljs2
# - nubank/matcher-combinators
# - exoscale/ex
# - fipp (for pretty printing)

# Update deps
clj -P  # Pre-download JVM deps
npm install  # Update npm deps if needed
```

### Step 2: Create New Files (10 min)

```bash
# Create test configuration
touch tests.edn

# Create new namespaces
touch dev/validation.cljs
touch src/kernel/errors.cljc
touch test/test_util.cljc
touch test/test_hooks.clj
```

### Step 3: Update bb.edn Tasks (5 min)

Add `test-watch`, `test-focus`, `test-fail-fast` tasks to bb.edn.

### Step 4: Migrate One Test File (10 min)

Pick a simple test file (e.g., `plugins/smart_editing_test.cljc`) and:
1. Use `test-util/assert-intent-ops` instead of raw `is`
2. Use `make-blocks` instead of manual tx/interpret
3. Run with `bb test-watch` to see improved output

### Step 5: Enable Validation in Dev (5 min)

Add validation wrapper to `dev/repl.cljs` or similar dev entry point.

---

## Expected Outcomes

### Before

```bash
# Run tests manually
bb test

# Output:
FAIL in (merge-with-next-test)
expected: (= "foo" actual)
  actual: (not (= "foo" "fo"))

# Agent response: "Can you add println to see what actual is?"
```

### After

```bash
# Tests auto-run on save
bb test-watch

# Output:
FAIL in plugins.smart-editing-test/merge-with-next-test
merge with next combines text

Expected: "First blockSecond block"
  Actual: "First block Second block"

  Diff:
  - "First blockSecond block"
  + "First block Second block"
           ↑
           extra space at position 11

Context:
  Intent: {:type :merge-with-next, :block-id "a"}
  Generated ops: [{:op :update-node, :id "a", :props {:text "First block Second block"}}]

# Agent response: "The merge is adding an extra space. Check line 67 in smart_editing.cljc
# where you concatenate prev-text and curr-text - you're probably including a trailing space."
```

### Validation Catching Bugs

```clojure
;; Code with typo:
(intent/apply-intent db {:type :update-content :bloc-id "a" :text "new"})

;; Before: Cryptic error 5 functions deep
;; "Cannot update node nil"

;; After: Immediate clear error at call site
;; ExceptionInfo: Intent validation failed: :update-content
;;   {:errors {:block-id ["missing required key"]
;;             :bloc-id ["disallowed key"]}}
```

---

## Optional: Advanced Features

### A. Cognitect Anomalies Pattern

For async operations that shouldn't use exceptions:

```clojure
;; deps.edn
io.github.cognitect-labs/anomalies {:git/sha "..."}

;; Usage in async intent handlers
(defn async-save-intent [db intent]
  (go
    (let [result (<! (save-to-server intent))]
      (if (anomaly? result)
        ;; Return anomaly as data (don't throw)
        {:db db
         :ops []
         :anomaly result}
        ;; Success
        {:db db
         :ops [{:op :update-node :id "status" :props {:saved true}}]}))))
```

### B. State-Flow for Integration Tests

If you want narrative-style integration tests:

```clojure
;; deps.edn
nubank/state-flow {:mvn/version "5.14.3"}

;; test/integration/editing_flow_test.clj
(deftest full-editing-workflow
  (state-flow/run
    (state-flow/flow "User edits and saves block"
      (db/clear!)
      (state-flow/verify "Creates initial block"
        (intent/apply :create-block {:text "Hello"})
        (db/has-block? "block-1"))
      (state-flow/verify "Edits block content"
        (intent/apply :enter-edit {:block-id "block-1"})
        (intent/apply :update-content {:block-id "block-1" :text "Hello World"})
        (db/block-text "block-1") => "Hello World")
      (state-flow/verify "Saves changes"
        (intent/apply :save)
        (db/saved?)))))
```

---

## Metrics & Success Criteria

### Quantitative

- ✅ Test failures include full diff output (100% of failures)
- ✅ Intent validation catches typos before transaction (target: 90%)
- ✅ Property test failures show shrunk minimal case (100% of property tests)
- ✅ Watch mode reduces manual test runs by 80%

### Qualitative

- ✅ AI agent can diagnose test failures from text output alone (no need to ask "can you add println?")
- ✅ Developers catch intent typos at call site instead of downstream
- ✅ Error messages include actionable context (node IDs, operations, db state)
- ✅ Test output is human AND machine readable

---

## References

### Libraries

- [kaocha](https://github.com/lambdaisland/kaocha) - Full-featured test runner
- [kaocha-cljs2](https://github.com/lambdaisland/kaocha-cljs2) - ClojureScript support
- [matcher-combinators](https://github.com/nubank/matcher-combinators) - Smart assertions
- [exoscale/ex](https://github.com/exoscale/ex) - Exception hierarchy
- [malli](https://github.com/metosin/malli) - Data validation
- [cognitect/anomalies](https://github.com/cognitect-labs/anomalies) - Error as data

### Patterns

- [Malli Function Instrumentation](https://github.com/metosin/malli/blob/master/docs/function-schemas.md)
- [Kaocha Hooks](https://cljdoc.org/d/lambdaisland/kaocha/1.91.1392/doc/10-hooks)
- [ClojureScript Error Handling](https://www.learn-clojurescript.com/section-4/lesson-24-handling-exceptions-and-errors/)
- [Idiomatic Clojure Errors](https://www.daveliepmann.com/articles/idiomatic-clojure-errors.html)

---

## Implementation Checklist

- [ ] Add dependencies to deps.edn
- [ ] Create tests.edn configuration
- [ ] Add bb.edn tasks (test-watch, test-focus, etc.)
- [ ] Create dev/validation.cljs
- [ ] Create src/kernel/errors.cljc
- [ ] Create test/test_util.cljc
- [ ] Create test/test_hooks.clj
- [ ] Enhance test/fixtures.cljc
- [ ] Migrate 1-2 test files to new utilities
- [ ] Document new testing patterns in TESTING.md
- [ ] Update CI workflow to use kaocha

---

**END OF SPEC**
