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
- **Self-Documenting Tests**: `test/evolver/feature_tests.cljs` - User story macros for living specifications
- **Test Helpers**: `test/evolver/test_helpers.cljs` - Environment-aware testing with agent validation

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

### Self-Documenting Test Framework

The new testing framework provides **living documentation** through user story macros:

```clojure
(deftest document-navigation-feature
  (jtbd "Navigate through nested content structures quickly"
    (user-story "Navigate between sibling nodes with keyboard shortcuts"
      (acceptance-criteria "Arrow Down moves to next sibling"
        (test-with-agent-validation
          ;; Test implementation with automatic agent observation
          (is true "Navigation works"))))))
```

### Environment-Aware Testing

Tests automatically adapt based on runtime environment:

#### ✅ SAFE TO TEST (All Environments):
- Pure functions (data transformations)
- Command registry lookups
- Schema definitions
- Data structure validation
- Error handling logic
- Non-browser utility functions

#### 🔧 ENHANCED IN BROWSER:
- Store state validation with `agent.store-inspector`
- UI state consistency checks
- Real DOM interaction testing via Chrome DevTools

#### ❌ AVOID IN NODE TESTS:
- DOM manipulation
- Browser event simulation
- Store/atom access that assumes browser context
- Keyboard event handling
- UI state verification

### Quick Test Fixes

#### When Tests Won't Run:
1. `npm test` - Runs all tests with environment detection
2. `npx shadow-cljs stop` + manual cleanup for cache issues
3. Verify browser is open at http://localhost:8080 for full test suite

#### Test Script Pattern:
```json
"test": "shadow-cljs compile test && node out/tests.js"
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

### Implemented Testing Architecture

#### Self-Documenting Feature Tests (`test/evolver/feature_tests.cljs`)
- User story macros (jtbd, user-story, acceptance-criteria)
- Living specifications that serve as executable documentation
- Agent tool integration for enhanced validation

#### Test Helpers (`test/evolver/test_helpers.cljs`)
- Environment-aware test execution
- Store state validation helpers
- Mock interaction utilities
- Agent observation wrappers

#### Chrome DevTools Integration (`test/evolver/chrome_integration_test.cljs`)
- Systematic keyboard shortcut validation
- Element interaction testing
- Rapid interaction stress testing
- Error injection and recovery testing
- Performance monitoring integration
- UI-DOM consistency validation

### Test Architecture Notes

- **Zero Breaking Changes**: Existing tests work unchanged
- **Environment Adaptive**: Tests enhance automatically in browser context
- **Living Documentation**: Tests serve as executable specifications
- **Agent Integration**: Store inspection and validation built-in
- **Chrome DevTools Ready**: UI testing framework enabled for real browser testing

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

1. **✅ IMPLEMENTED**: Self-documenting test framework with user story macros
2. **✅ IMPLEMENTED**: Agent tool integration for enhanced test validation
3. **✅ ENABLED**: Chrome DevTools integration framework (ready for MCP calls)
4. **Visual Regression**: Screenshot comparisons at interaction points
5. **State Migration Tests**: Backward compatibility for schema changes
6. **Mutation Testing**: Random code mutations to ensure test coverage

## Tool Commands That Work

```bash
npm run clean          # Full clean and reinstall
npm test              # Run complete test suite with environment detection
npx shadow-cljs stop  # Stop shadow-cljs server
npx shadow-cljs watch frontend  # Start development server
```

### Test Commands

```bash
npm test              # Full test suite (node + browser enhancements when available)
# Open http://localhost:8080 first for enhanced browser testing
```

## Key Takeaway

**Living Documentation Through Self-Documenting Tests**
- Tests serve as executable specifications with user story macros
- Environment-aware testing adapts automatically to runtime context
- Agent tools provide enhanced validation and debugging in browser
- Zero breaking changes - existing tests work unchanged
- Chrome DevTools integration enables comprehensive UI testing

**Test the Data Layer, Enhanced by Agent Tools**
- ClojureScript's strength is data transformation
- Agent tools add store inspection and validation automatically
- UI integration testing via Chrome DevTools when browser is available
- Focus on pure functions with optional browser enhancements
- Self-documenting tests create living specifications

## Agent Tools

src/agent/agent-docs.md: Comprehensive documentation for agent tools, listing purposes and public functions for each namespace with brief descriptions.

src/agent/core.cljc: **NEW GUARDRAILS ADDED** - Agent utilities with environment detection, command validation, schema validation, and namespace alignment checks. Provides unified access to store inspection, reference tools, and schema validation functions.

src/agent/doc-replicant.md: Documentation summary for the Replicant library covering data-driven event handling, hiccup format with specific features, reactive rendering with atoms, keys for optimization, state management patterns, and common usage patterns.

src/agent/reference_tools.cljc: Tools for debugging and inspecting the reference system, including functions to inspect all references, find orphaned references, validate reference integrity, simulate reference hover effects, get reference statistics, test reference operations, and perform comprehensive reference health checks.

src/agent/schemas.cljc: **ENHANCED** - Agent-specific schemas including new command parameter schemas for validation. Defines schemas for transactions, namespace health results, database structure, database diffs, operation results, and provides validation helpers for agent functions.

src/agent/store_inspector.cljc: Store inspection tools for debugging and analysis that provide functions to inspect store state with optional filtering, check reference integrity, get recent operation history with summaries, validate current selection for operations, get performance metrics, and perform quick state dumps.

## Testing Framework Tools

test/evolver/feature_tests.cljs: **NEW** - Self-documenting feature tests using user story macros (jtbd, user-story, acceptance-criteria) that serve as living specifications and executable documentation.

test/evolver/test_helpers.cljs: **NEW** - Test utilities providing environment-aware testing, agent tool integration, store state validation, and mock interaction helpers for comprehensive test coverage.

test/evolver/test_macros.cljc: **NEW** - Cross-platform macros for self-documenting tests including user story documentation macros and agent validation helpers.

test/evolver/chrome_integration_test.cljs: **ENABLED** - Chrome DevTools integration for systematic UI testing, keyboard shortcut validation, element interaction testing, and performance monitoring.

## Documentation References

@MCP-REPL-GUIDE.md: Complete ClojureScript REPL setup guide covering connection requirements, agent tool usage patterns, development workflows, browser console integration, and common debugging scenarios.

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

### ✅ ENABLED: Systematic UI Testing Framework

The Chrome DevTools integration is now **enabled** and ready for comprehensive UI testing:

- **Keyboard Shortcut Validation**: Systematic testing of all keyboard commands
- **Element Interaction Testing**: Click simulation and behavior validation
- **Rapid Interaction Stress Testing**: Performance testing under load
- **Error Injection & Recovery**: Fault tolerance validation
- **UI-DOM Consistency**: State-to-DOM synchronization checks

### Accessing CLJS Data in Browser Console

Browser console functions for debugging:
- `evo.inspectStore()` - Quick store state dump
- `evo.checkIntegrity()` - Reference system validation
- `evo.performance()` - Performance metrics

### Test Integration

Run comprehensive UI tests with:
```bash
# Full test suite (includes Chrome DevTools when browser available)
npm test

# With browser open at http://localhost:8080 for enhanced testing
# Tests automatically detect environment and enhance accordingly