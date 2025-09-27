# Agent Development Guide - ClojureScript Evolver App

## Common Errors & Failure Modes

### ✅ ELIMINATED by DX Tools

- **Selection state inconsistency**: Triple-field validation (selection/selection-set/cursor) prevents silent failures
- **Test context mismatches**: Mock DOM events and UI-mirroring test utilities fix test/UI dispatch differences  
- **Navigation silent failures**: Prerequisite validation warns when cursor missing before navigation commands
- **Watch loop crashes**: Automatic detection of infinite update cycles with configurable thresholds
- **Command execution opacity**: Full dispatch→command→state change tracing for debugging
- **Environment mismatches**: Environment detection prevents browser/node confusion with clear error messages
- **Schema validation failures**: Pre-execution schema validation catches invalid parameters before dispatch
- **JS-style data access**: Pattern validation detects incorrect property access on CLJS data structures
- **Nil dereferencing**: Safe wrapper functions prevent common nil-related crashes
- **Event handler format**: Validation ensures replicant action vectors follow correct format
- **Cache corruption**: Health monitoring detects and suggests cleanup for mysterious errors
- **CLJC compatibility**: Build target validation prevents browser APIs in node-test targets

### Still Active (Inherent to Environment)

- **No JS Runtime**: Browser not connected to shadow-cljs REPL (open http://localhost:8080 first)
- **Compilation Errors**: Syntax prevents hot reload
- **Missing Requires**: Unqualified imports (`set/difference` without `[clojure.set :as set]`) cause compilation failures
- **JS Literal Syntax**: Can't merge Clojure data directly in `#js` literals - use intermediate bindings
- **Malli Function Calls**: Must use `(m/validate)` not `(validate)` in CLJC files
- **Process Conflicts**: Multiple shadow-cljs processes break compilation (auto-detected by `npm run check-env`)
- **Event Conflicts**: Multiple handlers on same elements
- **Watch Failures**: Reactive updates not triggering renders
- **Dependency Order**: Compile frontend before test to ensure all namespaces are loaded
- **Store Delay/Atom Confusion**: Store defined as `delay` but used as atom - must dereference `@store` before watch operations
- **Missing Derived Metadata**: All kernel operations require `(k/update-derived db)` after test db creation
- **Chrome Console Overflow**: DevTools console logs exceed token limits - clear with `console.clear()` before testing

## DX Debugging Workflow

### State Validation
- **Selection Consistency**: `(agent/validate-selection-consistency (:view @store))` - Check triple-field synchronization
- **Navigation Prerequisites**: `(agent/validate-navigation-prerequisites @store :nav-down)` - Verify cursor is set
- **Store Integrity**: `(agent.store-inspector/check-reference-integrity store)` - Find orphaned references

### Command Tracing  
- **Execution Trace**: `(agent/traced-dispatch store event [:cmd params])` - Full dispatch→state change tracking
- **Watch Loops**: `(agent.store-inspector/track-watch-update :store)` - Detect infinite update cycles
- **Command History**: `(agent/get-command-trace)` - View recent command execution with timing

### Test Context Setup
- **Mock Events**: `(agent/create-mock-dom-event :target (agent/create-mock-target "node-id"))` - Proper DOM context
- **UI Test State**: `(agent/create-test-context initial-state :selection ["node"] :cursor "node")` - Consistent selection fields
- **Test Dispatch**: `(test-dispatch-commands store event commands)` - Use plural dispatch like UI

### Environment Validation
- **REPL Health**: `(agent/safe-repl-connect)` - Validate browser connection and shadow-cljs
- **Environment Check**: `(agent/check-development-environment)` - Comprehensive health check
- **Process Conflicts**: `npm run check-env` - Auto-detect shadow-cljs conflicts

## Agent Tool Quick Reference

### Core DX Functions (`agent.core`)
```clojure
(agent/help)                                     ; Complete function inventory
(agent/validate-selection-consistency view)     ; Fix triple-field issues
(agent/create-mock-dom-event :target target)    ; Mock events for tests
(agent/validate-navigation-prerequisites db op) ; Check cursor before nav
(agent/traced-dispatch store event cmd)         ; Command execution tracing
(agent/create-test-context state :cursor "id")  ; UI-consistent test state
```

### Store Inspection (`agent.store-inspector`)
```clojure
(agent.store-inspector/track-watch-update :key) ; Watch loop detection
(agent.store-inspector/check-reference-integrity store) ; Find orphans
(agent.store-inspector/inspect-store store :include-keys #{:view}) ; Filtered inspection
```

### Test Utilities (`test.evolver.test-helpers`)
```clojure
(test-dispatch-commands store event cmds)       ; UI-mirroring dispatch
(assert-selection-state-valid db)               ; Validate triple-field rule
(test-with-ui-context state test-fn)            ; Execute with proper context
```

## Architecture Invariants

### Replicant Event System
- **Event Format**: `:on {:click [[:command-id {:param value}]]}` - data vectors, not functions
- **Store Access**: Store may be `delay` - always dereference `@store` before watch operations
- **Rendering**: Parent nodes with children still show their own text - include in hiccup vector

### Intent System Pattern
- **Pure Functions**: `(intent-fn db params) -> [operations]` - no side effects
- **Test Setup**: Must call `(k/update-derived db)` after creating test databases
- **Data Structure**: `node-position` returns `{:parent :index :children}` where `:children` = siblings (not node's children)

### Chrome DevTools Integration
- **Console Management**: Call `console.clear()` before testing to avoid token overflow
- **Live Testing**: More reliable than ClojureScript REPL for UI validation
- **DOM Inspection**: Use snapshots to verify hierarchical content rendering

### Test Architecture
- **Immediate Validation**: Run tests immediately after writing - don't assume data structures
- **Derived Metadata**: All kernel tests need `update-derived` for tree operations to work
- **Browser Testing**: Use Chrome DevTools MCP for end-to-end validation over REPL

## ClojureScript REPL Setup

### Step-by-Step Connection
```bash
# 1. Start development environment
npm run dev                          # Starts shadow-cljs + nREPL
# Wait for: "HTTP server available at http://localhost:8080"

# 2. Open browser (CRITICAL - no browser = no JS runtime)
open http://localhost:8080           # Must be open before REPL connection
```

```clojure
;; 3. Connect to ClojureScript REPL
(shadow.cljs.devtools.api/repl :frontend)

;; 4. Verify connection works
(js/console.log "REPL connected successfully")
;; Check browser console for output

;; 5. Load agent tools
(require '[agent.core :as agent] :reload)
(agent/help)                         ; View available functions
```

### REPL Workflow
```clojure
;; Environment validation
(agent/safe-repl-connect)            ; Check browser connection
(agent/detect-environment)           ; Runtime capabilities

;; Store access patterns  
@evolver.core/store                  ; Full store state
(:view @evolver.core/store)          ; View state only
(get-in @evolver.core/store [:nodes "p1-select"]) ; Specific node

;; Live debugging
(add-watch evolver.core/store :debug
  (fn [k atom old new]
    (when (not= (:view old) (:view new))
      (js/console.log "View changed:" (clj->js (:view new))))))

;; Reload changes
(require '[evolver.core :as core] :reload)
(require '[agent.core :as agent] :reload)
```

### Browser Console Integration
```javascript
// Alternative access via browser console
evo.inspectStore()      // Quick store dump
evo.checkIntegrity()    // Reference integrity check  
evo.performance()       // Performance metrics
```

### REPL Constraints
- **Browser tab must stay open** - closing breaks connection
- **Shadow-cljs server must keep running** - stopping kills REPL
- **Use full namespaces**: `agent.store-inspector/inspect-store` not `inspect-store`
- **Agent tools work in `:frontend` target only** - not in node tests

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

### New Failure Modes (September 2025)

#### Compilation & Import Issues
- **Malli Unqualified Calls**: `(validate schema data)` → `(m/validate schema data)` in CLJC files
- **Missing Set Imports**: `set/difference` requires `[clojure.set :as set]` - auto-linting recommended
- **JS Literal Merging**: `#js (merge {...})` fails → use `(let [data (merge ...)] #js {:key (clj->js data)})`

#### Process Management
- **Shadow-cljs Conflicts**: Multiple processes break compilation - `npm run check-env` detects automatically
- **REPL Connection Issues**: Browser must be open first - `agent/safe-repl-connect` validates environment

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

## Development Environment Safety

### ⚠️ CRITICAL: Shadow-cljs Process Management

**Rule**: Never run manual `npx shadow-cljs` commands when `npm dev` is running

```bash
# ✅ SAFE: Use npm scripts (recommended)
npm dev               # Starts both shadow-cljs watch + nREPL
npm test              # Run complete test suite

# ❌ DANGEROUS: Manual shadow-cljs when npm dev is running
npx shadow-cljs watch frontend  # Creates process conflicts!
```

**Automated Detection**:
```bash
npm run check-env     # Automated process conflict detection
```

**Manual Detection**:
```bash
ps aux | grep "npm.*dev"
lsof -i :8080         # Check what's using the port
```

**Recovery from Process Conflicts**:
```bash
npm run clean         # Full reset: stop processes + clean cache
npm dev               # Restart clean
```

## Development Commands

### Environment Management
```bash
npm run dev           # Start development (shadow-cljs + nREPL)
npm run check-env     # Check for process conflicts and environment issues  
npm run clean         # Full reset: stop processes + clean cache + reinstall
npm test              # Run complete test suite with environment detection
npm run lint          # Run clj-kondo linting
npm run validate      # Run lint + test together
```

### Safe Development Workflow
```bash
# 1. Always check environment first
npm run check-env

# 2. Start development (handles process conflicts automatically)
npm run dev

# 3. Open browser BEFORE connecting REPL
open http://localhost:8080

# 4. Connect ClojureScript REPL and load agent tools
```

### Process Conflict Recovery
```bash
# If mysterious compilation failures:
npm run clean         # Nuclear option: full reset
npm run dev           # Restart clean

# If quick fix needed:
pkill -f 'shadow-cljs.*watch'  # Kill conflicting processes
npm run dev                    # Restart development
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

## Complete Agent Function Inventory

### DX Tools (September 2025 - Failure Mode Fixes)
- `agent/validate-selection-consistency`: Fix selection/selection-set/cursor triple-field inconsistencies  
- `agent/create-mock-dom-event`: Proper DOM events for testing UI commands
- `agent/create-mock-target`: Mock DOM targets with dataset properties
- `agent/validate-navigation-prerequisites`: Check cursor before navigation commands
- `agent/create-test-context`: Create UI-consistent test state with synchronized selection fields
- `agent/traced-dispatch`: Command execution tracing with full dispatch→state change tracking
- `agent/track-watch-update`: Watch loop detection with configurable thresholds
- `agent/get-command-trace`: View recent command execution history with timing

### Environment & Safety (Existing)
- `agent/detect-environment`: Detects current runtime (browser/node/store-accessible)
- `agent/safe-repl-connect`: Validates browser connection before REPL operations
- `agent/check-development-environment`: Comprehensive health check with recommendations
- `agent/validate-file-namespace-alignment`: Prevents namespace/filename mismatches
- `agent/validate-data-access-pattern`: Detects JS-style property access in CLJS code
- `agent/safe-name`, `agent/safe-first`, `agent/safe-get-in`: Nil-safe wrapper functions

### Command & Schema Validation (Existing)
- `agent/safe-command-dispatch`: Validates command exists before dispatch
- `agent/validate-operation-schema`: Validates data against Malli schemas
- `agent/validate-replicant-action-vector`: Validates replicant action vector format
- `agent/validate-call`: Manual function schema validation for interactive testing

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
```

## Summary: Key Agent Development Insights

### What Works Well (September 2025)
- ✅ **DX Tools**: Solve real failure modes from actual development sessions
- ✅ **Environment Detection**: Prevents browser/node context confusion
- ✅ **Process Management**: Automated conflict detection with `npm run check-env`
- ✅ **Test Framework**: Self-documenting tests with user story macros
- ✅ **REPL Workflow**: Reliable when following browser-first connection order

### Critical Success Factors
1. **Browser First**: Always open http://localhost:8080 before REPL connection
2. **Process Hygiene**: Use `npm run dev` not manual shadow-cljs commands
3. **State Validation**: Check selection consistency before operations
4. **Environment Validation**: Use `agent/check-development-environment` first
5. **Imports**: Explicit requires for `clojure.set`, `malli.core` in CLJC files

### Agent Tool Philosophy
- **Fix Real Problems**: Tools address actual development session failures
- **Environment Aware**: Adapt to browser vs node context automatically
- **Self-Documenting**: Functions include usage examples and suggestions
- **Non-Intrusive**: Integrate with existing workflow without breaking changes