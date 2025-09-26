# Agent Development Guide - ClojureScript Evolver App

## Common Errors & Failure Modes

### ✅ ELIMINATED by Guardrails

- **Unknown command errors**: Command registry validation now provides helpful error messages with available commands
- **Environment mismatches**: Environment detection prevents browser/node confusion with clear error messages
- **Schema validation failures**: Pre-execution schema validation catches invalid parameters before dispatch
- **Namespace/filename mismatches**: File edit tools now validate namespace alignment to prevent shadow-cljs compilation errors
- **JS-style data access**: Pattern validation detects incorrect property access on CLJS data structures
- **Nil dereferencing**: Safe wrapper functions prevent common nil-related crashes
- **Event handler format**: Validation ensures replicant action vectors follow correct format
- **Cache corruption**: Health monitoring detects and suggests cleanup for mysterious errors
- **CLJC compatibility**: Build target validation prevents browser APIs in node-test targets

### Still Active (Inherent to Environment)

- **No JS Runtime**: Browser not connected to shadow-cljs REPL (open http://localhost:8080 first)
- **Compilation Errors**: Syntax prevents hot reload
- **Event Conflicts**: Multiple handlers on same elements
- **Watch Failures**: Reactive updates not triggering renders
- **Source Path Build Targets**: Files in global `:source-paths` don't auto-compile into build targets (use `src/agent/` not `./agent/`)
- **Dependency Order**: Compile frontend before test to ensure all namespaces are loaded

## Debugging Patterns

- **Console Logging**: Use `(js/console.log ...)` in event handlers and operations
- **Selection State**: Verify `(:selected (:view @store))` before operations
- **Store Inspection**: Use `(require '[agent.core :as agent])` in ClojureScript REPL or `evo.inspectStore()` in browser console
- **Chrome DevTools**: Use click simulation and snapshot inspection for UI state
- **DOM Inspection**: Check element attributes and event bubbling with `document.querySelectorAll`
- **Render Testing**: Test individual render functions with `evolver.renderer.render_node(state, "root")`

## Agent Tool Usage

- **ClojureScript REPL**: `(require '[agent.core :as agent])` - Available after moving to `src/agent/`
- **Browser Console**: `evo.inspectStore()`, `evo.checkIntegrity()`, `evo.performance()` - Injected via Chrome DevTools
- **Automated Testing**: `test/evolver/agent_integration_test.cljs` - Chrome DevTools integration hooks

## ClojureScript REPL Setup

### Connection Requirements  
- **CRITICAL**: Open browser at http://localhost:8080 first (agent tools won't load without JS runtime)
- Connect with `(shadow/repl :frontend)` or use ClojureScript REPL
- Confirm with `(js/console.log "test")`
- Load agent tools: `(require '[agent.core :as agent] :reload)`

### Data Access Patterns
- ❌ Wrong: `store.state.view.selected` (CLJS ≠ JS objects)
- ✅ Right: `(let [state @store view (:view state) selected (:selected view)] selected)`

## Testing Architecture & Strategies

### Environment Mismatch Issues (BIGGEST TRAP)

#### Node vs Browser Environment
- **`:node-test` target runs in Node.js, NOT browser**
- Browser APIs (`js/document`, `js/window`, DOM) are NOT available in node tests
- Store atoms and browser-specific state are NOT accessible in node environment
- Tests that work in browser REPL will FAIL in `:node-test` target

```clojure
;; ❌ FAILS in node-test (but works in browser)
(deftest browser-test
  (testing "DOM access"
    (let [elements (js/document.querySelectorAll ".selected")]
      (is (> (count elements) 0)))))

;; ✅ WORKS in node-test
(deftest pure-function-test
  (testing "Pure functions"
    (is (= 4 (+ 2 2)))))
```

### Quick Test Fixes

#### When Tests Won't Run:
1. `npx shadow-cljs clean` (if available) or `npx shadow-cljs stop` + manual cleanup
2. Change test script to use `node` not `bun`
3. Verify compilation succeeds before running output
4. Check if test target includes needed source paths

#### Test Script Pattern:
```json
"test": "shadow-cljs compile test && node out/tests.js"
```

#### Cache Issues (Critical):
- Delete `.shadow-cljs/`, `out/`, `target/` directories
- Use `npx shadow-cljs stop` + manual cleanup (no valid `clean` command)
- Cache corruption causes mysterious failures
- Restart shadow-cljs server if running

```json
"clean:shadow": "npx shadow-cljs stop && rm -rf .shadow-cljs out target .cljs_node_repl"
```

### Command System Reality Check

#### Command Registry vs UI Integration Gap
- Core data operations (kernel functions) work perfectly
- UI tries to dispatch commands that DON'T EXIST in command registry
- Error messages are misleading - suggest commands called but not working, when commands don't exist

```clojure
;; ❌ Command doesn't exist in registry
[:select-node {:node-id "p1-select"}]  ; Results in "Unknown command"

;; ✅ Underlying function works fine
(kernel/some-operation db params)      ; Works perfectly
```

### What Works vs What Doesn't in Node Tests

#### ✅ SAFE TO TEST:
- Pure functions (data transformations)
- Command registry lookups
- Schema definitions
- Data structure validation
- Error handling logic
- Non-browser utility functions

#### ❌ AVOID IN NODE TESTS:
- DOM manipulation
- Browser event simulation
- Store/atom access that assumes browser context
- Keyboard event handling
- UI state verification
- Chrome DevTools integration

### Common Failure Patterns & Solutions

#### False Failure Patterns:
1. "Commands not working" → Commands don't exist in registry
2. "Selection not updating" → Tests running in wrong environment
3. "Store not accessible" → Browser store not available in node
4. "Keyboard events not working" → No DOM event system in node

#### Real Issue Detection:
```clojure
;; ❌ Test assumes browser context
(is (= #{} (get-store-selection)))  ; Fails because no browser store

;; ✅ Test the actual data layer
(is (= #{} (:selected (get-view-state test-db))))  ; Tests real logic
```

### Schema Validation Sync Issues

#### Schema Drift Problem
- Tests expect certain schema structure
- Code evolves, schemas change
- "Database validation failed: missing required key :derived"

```clojure
;; Test expects this structure
{:nodes {...} :view {...} :derived {...}}

;; But actual code doesn't always populate :derived
{:nodes {...} :view {...}}  ; Missing :derived key
```

### Property-Based Testing Limitations

#### Environment Restrictions
- Works fine in `.cljc` files for logic testing
- Fails in `:node-test` when trying to test browser interactions
- Chrome DevTools integration requires actual browser

```clojure
;; ❌ Property-based UI testing in node-test
(tc/quick-check 50 ui-interaction-property)  ; Fails, no DOM

;; ✅ Property-based logic testing
(tc/quick-check 100 pure-function-property)  ; Works great
```

### Recommended Test Structure

#### Separate by Environment:
```clojure
;; Pure logic tests (run in node-test)
(ns app.logic-test
  (:require [cljs.test :refer [deftest is]]))

;; Browser integration tests (run in browser-test or manual)
(ns app.browser-test
  (:require [cljs.test :refer [deftest is]]))
```

#### Test Only What Exists:
1. First verify command exists: `(commands/get-command :my-command)`
2. Then test command behavior
3. Don't test UI integration if commands aren't implemented

### Clean Test Suite Strategy

#### Start Minimal, Build Up:
1. Remove ALL broken tests initially
2. Keep only tests that pass and test real functionality
3. Add new tests incrementally as features are implemented
4. Don't test assumptions about what SHOULD work - test what DOES work

#### Result:
- Small test suite that actually passes
- Fast feedback loop
- Clear signal when something really breaks
- No noise from false failures

### Implemented Testing Architecture

#### Property-Based Testing (`test/evolver/fuzzy_ui_test.cljs`)
- Generates random interaction sequences (clicks + keyboard)
- Validates state invariants after each sequence
- Tests command parameter format consistency
- Catches contract violations between UI layers

#### Chrome DevTools Integration (`test/evolver/chrome_integration_test.cljs`)
- Systematic keyboard shortcut validation
- Element interaction testing
- Rapid interaction stress testing
- Error injection and recovery testing
- Performance monitoring integration
- UI-DOM consistency validation

### Test Architecture Notes

- Tests should be runnable without browser connection
- Avoid browser-specific APIs in unit tests
- Use integration tests for UI interactions via chrome-devtools
- Always clean cache when tests fail mysteriously
- Compilation warnings don't prevent tests but indicate real problems

## Current System Debugging

### Event Handling Issues
- **Event Bubbling**: Clicks bubble up, firing parent handlers
- **Multiple Handlers**: Conflicts between `:toggle-selection` and `:select-node`
- **Dispatch Debugging**: Add logging to trace event flow

### Replicant Rendering
- **Class Format**: Use `[:class ["selected"]]` not `[:class "selected"]`
- **Watch Setup**: `add-watch` on atom for reactive updates (call after main)
- **Manual Rerender**: Force with `(r/render root (renderer/render @store))`

### Selection Logic Problems
- **Root Clickable**: Root handler caused entire tree selection
- **Event Override**: Bubbling selected wrong elements
- **Fix**: Make only leaf nodes clickable, prevent root selection

### Reference System Gotchas
- **Selection Count**: Operations require exactly 2 selected nodes (fails silently otherwise)
- **UI Update Timing**: Store updates but DOM may lag due to rendering/watch issues
- **Command Validation**: Operations may succeed but not persist if validation fails
- **Rendering Bugs**: Text concatenation with children; prefer separate rendering

## Future Testing Improvements

1. **Enable Chrome DevTools Integration**: Replace placeholders with actual MCP calls
2. **Visual Regression**: Screenshot comparisons at interaction points
3. **State Migration Tests**: Backward compatibility for schema changes
4. **Mutation Testing**: Random code mutations to ensure test coverage

## Tool Commands That Work

```bash
npm run clean          # Full clean and reinstall
npm test              # Run node-test target
npx shadow-cljs stop  # Stop shadow-cljs server
```

## Key Takeaway

**Test the Data Layer, Not the UI Integration**
- ClojureScript's strength is data transformation
- UI integration has many environmental dependencies
- Focus tests on pure functions and data operations
- Use browser testing tools for actual UI verification
- Keep node tests for business logic only

## Agent Tools

src/agent/agent-docs.md: Comprehensive documentation for agent tools, listing purposes and public functions for each namespace with brief descriptions.

src/agent/core.cljc: **NEW GUARDRAILS ADDED** - Agent utilities with environment detection, command validation, schema validation, and namespace alignment checks. Provides unified access to store inspection, reference tools, and schema validation functions.

src/agent/doc-replicant.md: Documentation summary for the Replicant library covering data-driven event handling, hiccup format with specific features, reactive rendering with atoms, keys for optimization, state management patterns, and common usage patterns.

src/agent/reference_tools.cljc: Tools for debugging and inspecting the reference system, including functions to inspect all references, find orphaned references, validate reference integrity, simulate reference hover effects, get reference statistics, test reference operations, and perform comprehensive reference health checks.

src/agent/schemas.cljc: **ENHANCED** - Agent-specific schemas including new command parameter schemas for validation. Defines schemas for transactions, namespace health results, database structure, database diffs, operation results, and provides validation helpers for agent functions.

src/agent/store_inspector.cljc: Store inspection tools for debugging and analysis that provide functions to inspect store state with optional filtering, check reference integrity, get recent operation history with summaries, validate current selection for operations, get performance metrics, and perform quick state dumps.

## New Guardrail Functions

### Environment Detection
- `agent/detect-environment`: Detects current runtime (browser/node/store-accessible)
- `agent/validate-environment-for-operation`: Validates environment supports requested operation

### Command Validation
- `agent/safe-command-dispatch`: Validates command exists before dispatch
- `agent/safe-schema-validated-dispatch`: Full validation (environment + registry + schema)

### Schema Validation
- `agent/validate-operation-schema`: Validates data against Malli schemas
- `agent/validate-command-params`: Validates command parameters before execution

### File Safety
- `agent/validate-file-namespace-alignment`: Prevents namespace/filename mismatches

### Data Access Safety
- `agent/validate-data-access-pattern`: Detects JS-style property access in CLJS code
- `agent/safe-name`: Nil-safe version of name function
- `agent/safe-first`: Nil-safe version of first function
- `agent/safe-get-in`: Nil-safe version of get-in with better error messages

### Event Handler Validation
- `agent/validate-replicant-action-vector`: Validates replicant action vector format

### Build System Safety
- `agent/detect-cache-corruption`: Detects cache corruption symptoms and suggests cleanup
- `agent/validate-build-target-compatibility`: Validates .cljc files for target compatibility

## Chrome DevTools Integration

### Accessing CLJS Data in Browser Console