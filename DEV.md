# Development Guide

## Quick Start

```clojure
;; Load dev environment
(load-file "src/dev.clj")

;; Initialize REPL connection
(init!)

;; Check everything is working
(preflight-check!)
```

## Dev Environment API

See **[AGENTS.md](./AGENTS.md)** for complete architecture and testing infrastructure.

### Core Functions

#### Connection & Setup
- `(init!)` - Auto-connect to shadow-cljs nREPL, establishes browser REPL
- `(disconnect!)` - Clean shutdown of connections
- `(status)` - Show connection and environment status
- `(preflight-check!)` - Validate full stack (shadow-cljs, browser, nREPL)

#### Code Evaluation
- `(cljs! "code")` - Evaluate ClojureScript in browser context
- `(clj! "code")` - Evaluate Clojure in Node.js context  
- `(smart-eval! "code")` - Auto-detect context and evaluate appropriately

#### Application State
- `(inspect-store)` - View current application state
- `(inspect-store :path [:selection])` - View specific state path
- `(reload-app!)` - Reload application without losing REPL connection

#### Testing & Debugging
- `(trigger-command! :toggle-selection {:target-id "node-123"})` - Dispatch commands
- `(simulate-keypress! "ArrowDown")` - Simulate keyboard input
- `(set-test-state! {:nodes [...] :selection [...]})` - Set up test scenarios
- `(count-nodes)` - Get current node count
- `(dom-snapshot!)` - Capture DOM state for debugging

#### DOM Utilities
- `(get-dom-element "#selector")` - Query DOM elements
- `(assert-dom-count ".node" 5)` - Assert element counts for testing
- `(clear-console!)` - Clear browser console

## Testing Workflows

### Running Tests
```bash
# All Node.js tests with validation
npm test

# ClojureScript tests via REPL
npm run test:cljs

# Environment validation only  
npm run validate-env
```

### Interactive Testing
```clojure
;; Set up a test scenario
(set-test-state! {:nodes [{:id "test-1" :content "Hello"}
                          {:id "test-2" :content "World"}]
                  :selection []})

;; Interact with the application
(simulate-keypress! "ArrowDown")
(trigger-command! :toggle-selection {:target-id "test-1"})

;; Verify results
(inspect-store :path [:selection])
(count-nodes)
```

### Test Result Analysis
```bash
# View latest test results
cat ./test-results/latest-node-results.txt

# Check logs for debugging
ls ./logs/
```

## Architecture Patterns

### Command Pattern
All user actions become transactions:
```clojure
;; Intent functions return transaction vectors
(defn toggle-selection-intent [target-id]
  [{:op :update-view 
    :path [:selection] 
    :value (toggle-selection @current-state target-id)}])
```

### Pure Functions (Kernel)
Utility functions never mutate:
```clojure
;; Always return new values
(selected? state node-id)     ; → boolean
(toggle-selection state id)   ; → new state
(node-exists? state id)       ; → boolean
```

### REPL-Driven Development
1. Write function in file
2. Evaluate with `(cljs! "(require '[my.namespace :as ns] :reload)")`
3. Test interactively: `(cljs! "(ns/my-function test-data)")`
4. Verify in browser: `(inspect-store)` or `(dom-snapshot!)`

## Error Troubleshooting

### Connection Issues
- **nREPL not found**: Check `shadow-cljs watch frontend` is running
- **Browser disconnected**: Refresh localhost:8080, run `(init!)` again
- **Port conflicts**: Default ports 7888-7900, checks automatically

### Test Failures
- **"0 failures, 0 errors"** is success pattern
- Check `./logs/` for detailed error messages
- Use `(preflight-check!)` to validate environment

### Common Patterns
- **Always use** `npx shadow-cljs` in scripts
- **Project-local files** only (no /tmp dependencies)  
- **Environment validation** before operations
- **REPL reload** with `:reload` flag for latest code