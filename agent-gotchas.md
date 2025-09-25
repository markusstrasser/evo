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