# Agent Gotchas - Debugging Session Learnings

## ClojureScript REPL & Chrome DevTools Integration

### Connection Gotchas
- **JS Runtime Required**: REPL won't work until browser loads the app (`http://localhost:8080`)
- **Shadow-cljs Connection**: Must use `(shadow/repl :frontend)` to connect to browser build
- **Runtime Check**: `(js/console.log "test")` confirms connection

### Data Access Patterns
- **Wrong**: Direct property access like `store.state.view.selected` (CLJS data ≠ JS objects)
- **Right**: Use CLJS functions: `(let [state @store view (:view state) selected (:selected view)] selected)`

### Event Handling Issues
- **Event Bubbling**: Click events bubble up, causing parent handlers to fire
- **Multiple Handlers**: Old `:toggle-selection` handlers in data conflicted with new `:select-node` in renderer
- **Dispatch Debugging**: Add logging to dispatch function to trace event flow

### Replicant Rendering
- **Class Format**: Must use collections `[:class ["selected"]]` not strings `[:class "selected"]`
- **Watch Setup**: `add-watch` on atom for reactive updates, but ensure it's called after main
- **Manual Rerender**: Force render with `(r/render root (renderer/render @store))` when watch fails

### Selection Logic Problems
- **Root Clickable**: Root element had click handler causing entire tree selection
- **Event Override**: Multiple dispatches from bubbling selected wrong elements
- **Fix**: Make only leaf nodes clickable, prevent root selection

### Debugging Workflow
1. **Browser First**: Open `http://localhost:8080` before REPL
2. **Connect REPL**: `(shadow/repl :frontend)`
3. **Inspect Data**: Use `@store` and CLJS accessors
4. **Test Events**: Use chrome-devtools to simulate clicks
5. **Check DOM**: Verify rendering matches data state
6. **Force Updates**: Manual render when reactive updates fail

### Common Errors
- **No JS Runtime**: Browser not connected to shadow-cljs
- **Compilation Errors**: Syntax prevents hot reload
- **Data Access**: JS patterns on immutable CLJS data
- **Event Conflicts**: Multiple handlers on same elements
- **Watch Failures**: Reactive updates not triggering renders

### Key Fixes Applied
- Removed root click handler to prevent bubbling interference
- Used collection classes instead of string classes for replicant
- Added forced re-render in event handler as backup
- Ensured proper watch setup in main function

### Testing Integration
1. **Setup**: Browser open, REPL connected
2. **State Inspection**: Check atom state with CLJS functions
3. **User Actions**: Test clicks via chrome-devtools
4. **Verification**: Confirm DOM updates reflect state changes
5. **Edge Cases**: Test event bubbling and multiple handlers

## Reference System Implementation Gotchas

### Operation Execution Issues
- **Selection Count Mismatch**: Reference operations (add/remove) require exactly 2 selected nodes. If 0, 1, or >2 nodes selected, command creation fails silently.
- **UI Update Timing**: Store updates correctly but DOM may not reflect changes immediately due to rendering issues or watch firing delays.
- **Command Validation**: Operations return success but may not persist if validation fails or swap! doesn't trigger reactive updates.

### Rendering Bugs
- **Concatenated Text**: When nodes have children (like references sections), text content may concatenate with child elements in some rendering contexts.
- **Watch Failures**: Reactive rendering watches may not fire if old-state == new-state comparison fails or atom updates bypass the watch.
- **Replicant Children Handling**: Adding children to elements can cause unexpected text concatenation; prefer separate rendering for complex structures.

### Debugging Patterns
- **Add Console Logging**: Use (js/console.log ...) in event handlers and operations to trace execution flow.
- **Check Selection State**: Always verify (:selected (:view @store)) before applying operations.
- **Manual Store Inspection**: Use browser console with `evolver.core.store.cljs$core$IDeref$_deref$arity$1()` to inspect state.
- **Test with Chrome DevTools**: Use click simulation and snapshot inspection to verify UI state.

### Agent Tool Usage
- **Reference Tools**: Use agent/reference_tools.cljc for inspecting reference graphs and validating integrity.
- **Store Inspector**: Use agent/store-inspector.cljc for comprehensive state analysis and performance metrics.
- **Dev Tools**: Use agent/dev-tools.cljc for debugging helpers and state manipulation.

### Compilation Issues
- **Namespace Availability**: Agent namespaces may not be available in test builds; ensure source-paths include agent directory.
- **CLJC Compatibility**: .cljc files may not compile correctly in node-test targets; test in browser context first.
- **Dependency Order**: Compile frontend before test to ensure all namespaces are loaded.