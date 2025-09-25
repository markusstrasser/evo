# Agent Tool Usage Guide - REPL & Chrome DevTools

## ClojureScript REPL Gotchas

### Connection Requirements
- **Must have JS runtime**: REPL won't work until browser loads the app at `http://localhost:8080`
- **Need shadow-cljs connection**: Use `(shadow/repl :frontend)` to connect to the browser build
- **Runtime availability check**: `(js/console.log "test")` should work after proper connection

### Common Connection Issues
```clojure
;; ❌ This fails without browser runtime
(require '[evolver.core :as core] :reload)
;; Error: "No available JS runtime"

;; ✅ Connect to shadow-cljs first, then browser must be open
(shadow/repl :frontend)
;; Then navigate browser to localhost:8080
```

### Working with ClojureScript Data
```clojure
;; ❌ Direct property access doesn't work with CLJS data structures
store.state.view.selected

;; ✅ Use ClojureScript functions to access atoms and data
(let [state @store
      view (:view state)
      selected (:selected view)]
  selected)
```

## Chrome DevTools Integration

### Accessing CLJS Data in Browser Console
```javascript
// ❌ This won't work - CLJS data structures aren't JS objects
evolver.core.store.state.view.selected

// ✅ Deref the atom and use CLJS functions
const state = evolver.core.store.cljs$core$IDeref$_deref$arity$1();
const view = cljs.core.get(state, cljs.core.keyword("view"));
const selected = cljs.core.get(view, cljs.core.keyword("selected"));
```

### Testing User Interactions
- Use `chrome-devtools_take_snapshot` first to get element UIDs
- Click elements with `chrome-devtools_click` using the UID
- Check `chrome-devtools_list_console_messages` for React/replicant warnings
- Use `chrome-devtools_evaluate_script` to test state changes

### Debugging Render Issues
```javascript
// Check if components are loaded
typeof evolver !== 'undefined'
typeof evolver.core !== 'undefined'

// Test render functions individually
const state = evolver.core.store.cljs$core$IDeref$_deref$arity$1();
evolver.renderer.render_node(state, "root"); // Test specific nodes
```

## Shadow-cljs Compilation Issues

### Hot Reload Problems
- Syntax errors prevent compilation - fix syntax first
- Changes to `ns` declarations often require full restart
- Use `shadow-cljs watch :frontend` to see compilation status

### Common Error Patterns
```clojure
;; ❌ This causes "Doesn't support name" error
(name nil)

;; ✅ Guard against nil values
(when selected-op (name selected-op))
```

### Replicant Warnings
- **Class strings**: Use `["class1" "class2"]` instead of `"class1 class2"`
- **Event handlers**: Ensure proper vector format `[[:action-name {:data "value"}]]`
- Check browser console for replicant-specific warnings

## Testing Integration Flows

1. **Browser Setup**: Open `http://localhost:8080` first
2. **State Inspection**: Use browser console with proper CLJS accessors
3. **User Actions**: Test clicks/interactions via chrome-devtools
4. **State Verification**: Check atom state after each action
5. **Render Verification**: Ensure UI updates reflect state changes

## Key Failure Modes

- **No JS Runtime**: Browser not connected to shadow-cljs REPL
- **Compilation Errors**: Syntax issues prevent hot reload
- **Data Access**: Using JS patterns on CLJS immutable data
- **Nil Dereferencing**: Functions like `name` failing on nil values
- **Event Handler Format**: Incorrect replicant action vector format