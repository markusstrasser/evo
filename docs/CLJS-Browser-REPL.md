# ClojureScript Browser REPL Integration Guide

## Overview

Generic REPL tooling for shadow-cljs development. No application-specific assumptions.

## Quick Start

```clojure
;; 1. Ensure shadow-cljs is running
;; npm run dev

;; 2. Load REPL helpers
(require '[repl :as repl])

;; 3. Initialize environment
(repl/init!)  ; Loads core.{db,ops,interpret}, fixtures

;; 4. Evaluate ClojureScript in browser
(repl/cljs! "(js/console.log \"Hello browser!\")")
(repl/cljs! "js/document.title")
```

## Architecture

```
REPL             Shadow-cljs Server       Browser
            ←→   (Port 55449 nREPL)  ←→   (WebSocket)
                       ↑                     ↑
                 Compilation             Live App
                 Hot Reload              DOM APIs
```

**Key**: Use shadow-cljs REPL API to evaluate code in browser context.

## Core Functions

### Connection Management (`dev/repl.clj`)

```clojure
(repl/connect!)  ; Connect to shadow-cljs REPL for :frontend build
(repl/init!)     ; Load core namespaces + fixtures
(repl/cljs! "code")  ; Evaluate ClojureScript in browser
(repl/clj! "code")   ; Evaluate Clojure in JVM
```

### Health Checks (`dev/health.clj`)

```clojure
(require '[health :as h])
(h/preflight-check!)       ; Validate environment
(h/cache-stats)            ; Show cache sizes
(h/check-shadow-conflicts) ; Detect process conflicts
(h/clear-caches!)          ; Nuclear option
```

## Usage Patterns

### Basic Browser Interaction
```clojure
;; DOM access
(repl/cljs! "js/document.title")
(repl/cljs! "js/document.querySelector(\".node\")")

;; Console output
(repl/cljs! "(js/console.log \"Debug info\")")

;; Browser APIs
(repl/cljs! "js/localStorage.getItem(\"key\")")
```

### Development Operations
```clojure
;; Test core operations in browser
(repl/cljs! "(require '[core.ops :as ops])")
(repl/cljs! "(ops/create-node {} \"id\" :div {})")

;; Load test fixtures
(repl/cljs! "(require '[fixtures :as fix])")
(repl/cljs! "(fix/gen-flat-tree 5)")
```

## Prerequisites

### Required Running Processes
1. **Shadow-cljs server**: `npm run dev` (creates nREPL on port 55449)
2. **Browser connection**: Open http://localhost:8080

### File Structure
```
dev/
  repl.clj        - REPL bridge
  health.clj      - Health checks
  fixtures.cljc   - Test data builders
```

## Error Recovery

### Connection Issues
```clojure
;; Test basic evaluation
(repl/cljs! "(+ 1 1)")  ; Should return 2

;; Reconnect if needed
(repl/connect!)
```

### Common Error Messages
- **"shadow-cljs has not been started yet!"** → Run `npm run dev`
- **"Connection refused"** → Check shadow-cljs is running on port 55449
- **"No such namespace"** → Ensure namespace is loaded in browser build

## Advanced Usage

### Multi-Expression Evaluation
```clojure
(repl/cljs! "
(def debug-data {:timestamp (js/Date.now)
                 :title js/document.title})
(js/console.log \"Debug:\" debug-data)
debug-data")
```

### Working with Fixtures
```clojure
;; Load fixtures in browser context
(repl/cljs! "(require '[fixtures :as fix])")
(repl/cljs! "(def tree (fix/gen-balanced-tree 2 3))")
(repl/cljs! "(:db tree)")

;; Test operations
(repl/cljs! "(require '[core.ops :as ops])")
(repl/cljs! "(ops/create-node (:db tree) \"new\" :span {})")
```

## Integration with Chrome DevTools

When browser is open at http://localhost:8080, ClojureScript evaluation automatically appears in:
- **Console tab**: `js/console.log` output
- **Sources tab**: Hot reload changes
- **Application tab**: LocalStorage/DOM modifications

## Troubleshooting Checklist

1. **Shadow-cljs running?** `ps aux | grep shadow`
2. **Browser connected?** Visit http://localhost:8080
3. **nREPL responsive?** `lsof -i :55449`
4. **Basic eval works?** `(repl/cljs! "(+ 1 2)")`

## Success Validation

Run this test sequence:

```clojure
(require '[repl :as repl])
(repl/init!)
(repl/cljs! "(+ 1 2 3)")  ; → 6
(repl/cljs! "js/document.title")  ; → Page title
(repl/cljs! "(js/console.log \"✅ Integration working!\")")
```

If all evaluations succeed, ClojureScript browser REPL is functional.

---

## Summary

This tooling provides **zero-friction ClojureScript evaluation** by:
1. **Using shadow-cljs REPL API** for browser evaluation
2. **Providing simple helpers** (connect!, init!, cljs!)
3. **No application assumptions** - works with any ClojureScript project
4. **Clean separation** - REPL bridge separate from application code

The result: Simple, generic REPL tooling for ClojureScript development.

## See Also
- `dev/README.md` - Complete dev tooling documentation
- `docs/DEV.md` - Development workflows
- Shadow-cljs documentation: https://shadow-cljs.github.io/