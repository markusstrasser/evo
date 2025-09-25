
### Common Errors

• No JS Runtime: Browser not connected to shadow-cljs
• Compilation Errors: Syntax prevents hot reload
• Data Access: JS patterns on immutable CLJS data
• Event Conflicts: Multiple handlers on same elements
• Watch Failures: Reactive updates not triggering renders

### Key Fixes Applied

• Removed root click handler to prevent bubbling interference
• Used collection classes instead of string classes for replicant
• Added forced re-render in event handler as backup
• Ensured proper watch setup in main function

Replicant Children Handling: Adding children to elements can cause unexpected text concatenation; prefer separate rendering for complex structures.

#### Debugging Patterns

• Add Console Logging: Use (js/console.log ...) in event handlers and operations to trace execution flow.
• Check Selection State: Always verify (:selected (:view @store)) before applying operations.
• Manual Store Inspection: Use browser console with evolver.core.store.cljs$core$IDeref$_deref$arity$1() to inspect state.
• Test with Chrome DevTools: Use click simulation and snapshot inspection to verify UI state.

## Agent Tool Usage

### Reference Tools

• Use agent/reference_tools.cljc for inspecting reference graphs and validating integrity.

### Store Inspector

• Use agent/store-inspector.cljc for comprehensive state analysis and performance metrics.

### Dev Tools

• Use agent/dev-tools.cljc for debugging helpers and state manipulation.

## Testing Integration Flows

1. Browser Setup: Open http://localhost:8080 first
2. State Inspection: Use browser console with proper CLJS accessors
3. User Actions: Test clicks/interactions via chrome-devtools
4. DOM Verification: Check actual CSS classes and element selection
5. State Verification: Check atom state after each action
6. Render Verification: Ensure UI updates reflect state changes

### Debugging Workflow

1. Browser First: Open http://localhost:8080 before REPL
2. Connect REPL: (shadow/repl :frontend)
3. Inspect Data: Use @store and CLJS accessors
4. Test Events: Use chrome-devtools to simulate clicks
5. Check DOM: Verify rendering matches data state
6. Force Updates: Manual render when reactive updates fail

## Key Failure Modes

• No JS Runtime: Browser not connected to shadow-cljs REPL
• Compilation Errors: Syntax issues prevent hot reload
• Data Access: Using JS patterns on CLJS immutable data
• Nil Dereferencing: Functions like name failing on nil values
• Event Handler Format: Incorrect replicant action vector format
• Namespace Availability: Agent namespaces may not be available in test builds; ensure source-paths include agent directory
• CLJC Compatibility: .cljc files may not compile correctly in node-test targets; test in browser context first
• Dependency Order: Compile frontend before test to ensure all namespaces are loaded

### Common Errors

• No JS Runtime: Browser not connected to shadow-cljs
document.querySelectorAll('.selected')

// Inspect element attributes and handlers
document.querySelectorAll('.node').forEach(el => console.log(el.className, el.attributes))

// Debug event bubbling - check if events propagate to parent elements
// Use chrome-devtools_click then inspect which element gets selected class

## Current System Debugging

### Connection Gotchas

• JS Runtime Required: REPL won't work until browser loads the app (http://localhost:8080)
• Shadow-cljs Connection: Must use (shadow/repl :frontend) to connect to browser build
• Runtime Check: (js/console.log "test") confirms connection

### Data Access Patterns

• Wrong: Direct property access like store.state.view.selected (CLJS data ≠ JS objects)
• Right: Use CLJS functions: (let [state @store view (:view state) selected (:selected view)] selected)

### Event Handling Issues

• Event Bubbling: Click events bubble up, causing parent handlers to fire
• Multiple Handlers: Old :toggle-selection handlers in data conflicted with new :select-node in renderer
• Dispatch Debugging: Add logging to dispatch function to trace event flow

### Replicant Rendering

• Class Format: Must use collections [:class ["selected"]] not strings [:class "selected"]
• Watch Setup: add-watch on atom for reactive updates, but ensure it's called after main
• Manual Rerender: Force render with (r/render root (renderer/render @store)) when watch fails

### Selection Logic Problems

• Root Clickable: Root element had click handler causing entire tree selection
• Event Override: Multiple dispatches from bubbling selected wrong elements
• Fix: Make only leaf nodes clickable, prevent root selection

### Reference System Implementation Gotchas

#### Operation Execution Issues

• Selection Count Mismatch: Reference operations (add/remove) require exactly 2 selected nodes. If 0, 1, or >2 nodes selected, command creation fails silently.
• UI Update Timing: Store updates correctly but DOM may not reflect changes immediately due to rendering issues or watch firing delays.
• Command Validation: Operations return success but may not persist if validation fails or swap! doesn't trigger reactive updates.

#### Rendering Bugs

• Concatenated Text: When nodes have children (like references sections), text content may concatenate with child elements in some rendering contexts.
• Watch Failures: Reactive rendering watches may not fire if old-state == new-state comparison fails or atom updates bypass the watch.
• Replicant Children Handling: Adding children to elements can cause unexpected text concatenation; prefer separate rendering for complex structures.

#### Debugging Patterns

• Add Console Logging: Use (js/console.log ...) in event handlers and operations to trace execution flow.

### Common Errors

• No JS Runtime: Browser not connected to shadow-cljs
• Compilation Errors: Syntax prevents hot reload
• Data Access: JS patterns on immutable CLJS data
• Event Conflicts: Multiple handlers on same elements
• Watch Failures: Reactive updates not triggering renders

### Key Fixes Applied

• Removed root click handler to prevent bubbling interference
• Used collection classes instead of string classes for replicant
• Added forced re-render in event handler as backup
• Ensured proper watch setup in main function

// ❌ This won't work - CLJS data structures aren't JS objects
evolver.core.store.state.view.selected

// ✅ Deref the atom and use CLJS functions
const state = evolver.core.store.cljs$core$IDeref$_deref$arity$1();
const view = cljs.core.get(state, cljs.core.keyword("view"));
const selected = cljs.core.get(view, cljs.core.keyword("selected"));

### Testing User Interactions

• Use chrome-devtools_take_snapshot first to get element UIDs
• Click elements with chrome-devtools_click using the UID
• Check chrome-devtools_list_console_messages for React/replicant warnings
• Use chrome-devtools_evaluate_script to test state changes

### Debugging Render Issues

// Check if components are loaded
typeof evolver !== 'undefined'
typeof evolver.core !== 'undefined'

// Test render functions individually
const state = evolver.core.store.cljs$core$IDeref$_deref$arity$1();
evolver.renderer.render_node(state, "root"); // Test specific nodes

## Shadow-cljs Development

### Hot Reload Problems


# Agent Development Guide - ClojureScript Evolver App

## ClojureScript REPL Setup

### Connection Requirements

• Must have JS runtime: REPL won't work until browser loads the app at http://localhost:8080
• Need shadow-cljs connection: Use (shadow/repl :frontend) to connect to the browser build
• Runtime availability check: (js/console.log "test") should work after proper connection

### Common Connection Issues

;; ❌ This fails without browser runtime
(require '[evolver.core :as core] :reload)
;; Error: "No available JS runtime"

;; ✅ Connect to shadow-cljs first, then browser must be open
(shadow/repl :frontend)
;; Then navigate browser to localhost:8080

### Working with ClojureScript Data

;; ❌ Direct property access doesn't work with CLJS data structures
store.state.view.selected

;; ✅ Use ClojureScript functions to access atoms and data
(let [state @store
view (:view state)
selected (:selected view)]
selected)

## Chrome DevTools Integration

### Accessing CLJS Data in Browser Console
