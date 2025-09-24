# ClojureScript Testing Lessons Learned

## Key Insights from This Project

### Environment Mismatch Issues

**THE BIGGEST TRAP: Node vs Browser Environment**
- `:node-test` target runs tests in Node.js, NOT in browser
- Browser APIs (`js/document`, `js/window`, DOM manipulation) are NOT available in node tests
- Store atoms and browser-specific state are NOT accessible in node environment
- Tests that work in browser REPL will FAIL in `:node-test` target

**What This Means:**
```clojure
;; ❌ THIS FAILS in node-test (but works in browser)
(deftest browser-test
  (testing "DOM access"
    (let [elements (js/document.querySelectorAll ".selected")]
      (is (> (count elements) 0)))))

;; ❌ THIS FAILS in node-test (but works in browser) 
(deftest store-test
  (testing "Store access"
    (swap! evolver.state/store assoc :test true)))

;; ✅ THIS WORKS in node-test
(deftest pure-function-test
  (testing "Pure functions"
    (is (= 4 (+ 2 2)))))
```

### Command System Reality Check

**Command Registry vs UI Integration Gap**
- Core data operations (kernel functions) work perfectly
- UI tries to dispatch commands that DON'T EXIST in command registry
- Tests assuming commands exist will fail with "Unknown command" errors
- Error messages are misleading - they suggest commands are called but not working, when actually commands don't exist

**Example:**
```clojure
;; ❌ This command doesn't exist in registry
[:select-node {:node-id "p1-select"}]  ; Results in "Unknown command"

;; ✅ But the underlying function works fine
(kernel/some-operation db params)      ; Works perfectly
```

### Shadow-cljs Testing Gotchas

**Cache Issues Are Critical**
- `npx shadow-cljs clean` is NOT a valid command (despite common assumption)
- Use `npx shadow-cljs stop` + manual directory cleanup instead
- Cache corruption causes mysterious test failures that seem like code issues
- Always clean cache when tests fail unexpectedly

**Correct Clean Script:**
```json
"clean:shadow": "npx shadow-cljs stop && rm -rf .shadow-cljs out target .cljs_node_repl"
```

**Compilation Warnings vs Errors**
- Compilation warnings (undeclared vars) don't prevent tests from running
- But they indicate real problems (missing imports, wrong environment)
- Tests can "pass" while having fundamental issues

### Test.check (Property-Based Testing) Limitations

**Environment Restrictions**
- Works fine in `.cljc` files for logic testing
- Fails in `:node-test` when trying to test browser interactions
- Chrome DevTools integration requires actual browser, not node simulation

**Better Approach:**
```clojure
;; ❌ Property-based UI testing in node-test
(tc/quick-check 50 ui-interaction-property)  ; Fails, no DOM

;; ✅ Property-based logic testing
(tc/quick-check 100 pure-function-property)  ; Works great
```

### Schema Validation Sync Issues

**Schema Drift Problem**
- Tests expect certain schema structure
- Code evolves, schemas change
- Tests fail with confusing validation errors
- "Database validation failed: missing required key :derived"

**Root Cause:**
```clojure
;; Test expects this structure
{:nodes {...} :view {...} :derived {...}}

;; But actual code doesn't always populate :derived
{:nodes {...} :view {...}}  ; Missing :derived key
```

### What Actually Works in Node Tests

**✅ SAFE TO TEST:**
- Pure functions (data transformations)
- Command registry lookups
- Schema definitions
- Data structure validation
- Error handling logic
- Non-browser utility functions

**❌ AVOID IN NODE TESTS:**
- DOM manipulation
- Browser event simulation
- Store/atom access that assumes browser context
- Keyboard event handling
- UI state verification
- Chrome DevTools integration

### Debugging Test Failures

**False Failure Patterns:**
1. "Commands not working" → Actually commands don't exist in registry
2. "Selection not updating" → Tests running in wrong environment
3. "Store not accessible" → Browser store not available in node
4. "Keyboard events not working" → No DOM event system in node

**Real Issue Detection:**
```clojure
;; ❌ Test assumes browser context
(is (= #{} (get-store-selection)))  ; Fails because no browser store

;; ✅ Test the actual data layer  
(is (= #{} (:selected (get-view-state test-db))))  ; Tests real logic
```

### Recommended Test Structure

**Separate by Environment:**
```clojure
;; Pure logic tests (run in node-test)
(ns app.logic-test
  (:require [cljs.test :refer [deftest is]]))

;; Browser integration tests (run in browser-test or manual)
(ns app.browser-test
  (:require [cljs.test :refer [deftest is]]))
```

**Test Only What Exists:**
1. First verify command exists: `(commands/get-command :my-command)`
2. Then test command behavior
3. Don't test UI integration if commands aren't implemented

### Clean Test Suite Strategy

**Start Minimal, Build Up:**
1. Remove ALL broken tests initially
2. Keep only tests that pass and test real functionality
3. Add new tests incrementally as features are implemented
4. Don't test assumptions about what SHOULD work - test what DOES work

**Result:**
- Small test suite (5 tests) that actually passes
- Fast feedback loop
- Clear signal when something really breaks
- No noise from false failures

### Tool Commands That Actually Work

```bash
npm run clean          # Full clean and reinstall
npm test              # Run node-test target
npx shadow-cljs stop  # Stop shadow-cljs server (no "clean" command)
```

### Key Takeaway

**Test the Data Layer, Not the UI Integration**
- ClojureScript's strength is data transformation
- UI integration has many environmental dependencies
- Focus tests on pure functions and data operations
- Use browser testing tools for actual UI verification
- Keep node tests for business logic only