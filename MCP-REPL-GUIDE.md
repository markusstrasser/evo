# MCP ClojureScript REPL & Agent Tools Guide

## Connection Requirements

### 1. Start shadow-cljs
```bash
npx shadow-cljs watch frontend
```

### 2. Open Browser Runtime
**CRITICAL**: Open http://localhost:8080 in browser before REPL work
- ClojureScript REPL requires active browser tab for JS runtime
- No browser = "No available JS runtime" errors

### 3. Connect to Frontend REPL
```clojure
(shadow.cljs.devtools.api/repl :frontend)
```

### 4. Verify Connection
```clojure
(js/console.log "REPL connected successfully")
;; Check browser console for output
```

## Agent Tools Usage

### Environment Detection
```clojure
(agent/detect-environment)
;; => {:browser? true, :node? false, :store-accessible? true, :cljs-repl? false}
```

### Store Inspection
```clojure
;; Full store summary
(agent.store-inspector/inspect-store evolver.core/store)
;; => {:store-summary {:node-count 7, :selected-count 1, :reference-count 1}, 
;;     :filtered-data {...}}

;; Quick state dump
(agent.store-inspector/quick-state-dump evolver.core/store)
;; => "Nodes: 7, Selected: 1 (p1-select), References: 1 entries"

;; Filtered inspection
(agent.store-inspector/inspect-store evolver.core/store :include-keys #{:nodes :view})
```

### Store Access Patterns
```clojure
;; Access store state
@evolver.core/store

;; Get current selection
(:selected (:view @evolver.core/store))

;; Check specific node
(get-in @evolver.core/store [:nodes "p1-select"])
```

## Development Workflow

### 1. Live Debugging
```clojure
;; Watch store changes
(add-watch evolver.core/store :debug
  (fn [key atom old-state new-state]
    (when (not= (:view old-state) (:view new-state))
      (js/console.log "View changed:" (clj->js (:view new-state))))))

;; Remove watch
(remove-watch evolver.core/store :debug)
```

### 2. Testing Changes
```clojure
;; Reload namespace with changes
(require '[evolver.core :as core] :reload)

;; Test agent tools after changes
(require '[agent.core :as agent] :reload)
(agent/detect-environment)
```

### 3. Manual State Manipulation (for testing)
```clojure
;; Simulate selection change
(swap! evolver.core/store assoc-in [:view :selected] #{"p2-high" "p3-both"})

;; Trigger re-render
(evolver.core/handle-event {} [[:toggle-selection {:target-id "p4-click"}]])
```

## Browser Console Integration

The agent tools also expose functions to browser console via `window.evo`:

```javascript
// In browser console
evo.inspectStore()      // Quick store dump
evo.checkIntegrity()    // Reference integrity check
evo.performance()       // Performance metrics
```

## Invariant Constraints

### REPL Dependencies
- **Browser tab must remain open** - closing tab breaks REPL connection
- **shadow-cljs server must stay running** - stopping server kills REPL
- **Frontend build must include agent namespaces** - they won't be available otherwise

### Namespace Access
- Use full namespace paths in REPL: `agent.store-inspector/inspect-store`
- Agent namespaces available: `agent.core`, `agent.store-inspector`
- Main store accessible via: `evolver.core/store`

### Build Target Isolation
- Agent tools work in `:frontend` target (browser REPL)
- Different build targets have different classpath visibility
- Always test agent functionality in browser context, not node tests

### File Naming Conventions
- Filename must match namespace: `store_inspector.cljc` → `agent.store-inspector`
- ClojureScript is strict about filename/namespace alignment
- Use underscores in filenames, hyphens in namespace names

## Common Gotchas

### Cache Issues
If namespace resolution fails mysteriously:
```bash
npx shadow-cljs stop
rm -rf .shadow-cljs out target
npx shadow-cljs watch frontend
```

### Missing Dependencies
If agent namespace won't load, check requires:
```clojure
;; In agent file
(ns agent.store-inspector
  (:require [clojure.set]      ; Required for set operations
            [clojure.string])) ; Required for string operations
```

### Environment Mismatch
Agent tools require browser context:
- `js/window` available = browser ✓
- `js/process` available = node (agent tools may not work)

## Quick Reference

```clojure
;; Essential commands
(agent/detect-environment)                           ; Check environment
(agent.store-inspector/quick-state-dump evolver.core/store)  ; Quick status
@evolver.core/store                                  ; Full store state
(:view @evolver.core/store)                         ; Just view state
(js/console.log "debug message")                     ; Console output
```