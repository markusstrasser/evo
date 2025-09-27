# ClojureScript Browser REPL Integration Guide

## Overview

Complete solution for MCP agents to seamlessly evaluate ClojureScript code in browser context via shadow-cljs. This guide solves the "process isolation challenge" that previously blocked browser REPL access.

## Quick Start

```clojure
;; 1. Ensure shadow-cljs is running
;; npm run dev

;; 2. One-command setup
(require '[dev-mcp :as dmcp])
(dmcp/init!)

;; 3. Evaluate ClojureScript in browser
(dmcp/cljs! "(js/console.log \"Hello browser!\")")
(dmcp/cljs! "js/document.title")  ; → "Evolver"
(dmcp/cljs! "(keys @@evolver.core/store)")  ; → (:past :present :future :view)
```

## Architecture

```
MCP REPL          Shadow-cljs Server       Browser
(Port 7888)  ←→   (Port 55449 nREPL)  ←→   (WebSocket)
    ↑                     ↑                     ↑
  AI Agent          Compilation             Live App
  Tooling           Hot Reload              DOM APIs
```

**Key Insight**: Use nREPL client pattern to connect TO shadow-cljs rather than trying to hijack its process.

## Core Functions

### Connection Management (`src/dev.clj`)

```clojure
(dev/connect-to-shadow!)        ; Connect to shadow-cljs nREPL
(dev/switch-to-frontend!)       ; Switch to ClojureScript context
(dev/status)                    ; Check connection status  
(dev/disconnect-shadow!)        ; Clean disconnect
```

### Evaluation

```clojure
(dev/cljs! "code")              ; Raw ClojureScript evaluation
(dev/shadow-eval "code")        ; Low-level nREPL message handling
```

### Simplified Interface (`src/dev-mcp.clj`)

```clojure
(dmcp/init!)                    ; Auto-connect + context switch
(dmcp/cljs! "code")             ; Convenient evaluation
(dmcp/help!)                    ; Usage documentation
```

## Usage Patterns

### Basic Browser Interaction
```clojure
;; DOM access
(dmcp/cljs! "js/document.title")
(dmcp/cljs! "js/document.querySelector(\".node\")")

;; Console output  
(dmcp/cljs! "(js/console.log \"Debug info:\" some-data)")

;; Browser APIs
(dmcp/cljs! "js/localStorage.getItem(\"key\")")
```

### Application State Access
```clojure
;; View store structure
(dmcp/cljs! "(keys @@evolver.core/store)")

;; Get current view state
(dmcp/cljs! "(:view @@evolver.core/store)")

;; Access specific data
(dmcp/cljs! "(get-in @@evolver.core/store [:nodes \"node-id\"])")
```

### Development Operations
```clojure
;; Reload application
(dmcp/cljs! "(evolver.core/main)")

;; Trigger app commands
(dmcp/cljs! "(evolver.dispatcher/dispatch! [:select-node {:id \"n1\"}])")

;; Test functions interactively
(dmcp/cljs! "(evolver.intents/navigation-intent @db :nav-down {})")
```

## Prerequisites & Environment

### Required Running Processes
1. **Shadow-cljs server**: `npm run dev` (creates nREPL on port 55449)
2. **Browser connection**: Open http://localhost:8080
3. **MCP REPL**: Connected to project nREPL (port 7888)

### Dependency Requirements
```clojure
;; In deps.edn (already configured)
{:deps {shadow-cljs/shadow-cljs {:mvn/version "2.x.x"}
        nrepl/nrepl {:mvn/version "1.3.1"}}}
```

### File Dependencies
- `src/dev.clj` - Core bridge implementation
- `src/dev_mcp.clj` - Simplified MCP interface

## Error Recovery

### Connection Issues
```clojure
;; Check if shadow-cljs is running
(dmcp/cljs! "(+ 1 1)")  ; Should return 2

;; If connection lost, reconnect
(dev/disconnect-shadow!)
(dmcp/init!)
```

### Context Problems
```clojure
;; If getting "No such namespace: js" errors
(dev/switch-to-frontend!)

;; Verify browser context
(dmcp/cljs! "js/console.log(\"Browser context active\")")
```

### Common Error Messages
- **"shadow-cljs has not been started yet!"** → Run `npm run dev`
- **"Connection refused"** → Check shadow-cljs nREPL port (55449)
- **"No such namespace: js"** → Switch to ClojureScript context
- **"is not ISeqable"** → Use `@@store` not `@store` for app state

## Advanced Usage

### Context Switching
```clojure
;; Work in ClojureScript
(dmcp/cljs! "(js/console.log \"In browser\")")

;; Switch back to Clojure  
(dmcp/cljs! ":cljs/quit")
(+ 1 2 3)  ; Now in Clojure context

;; Return to ClojureScript
(dev/switch-to-frontend!)
```

### Direct nREPL Control
```clojure
;; Access raw nREPL connection
(dev/shadow-eval "(js/performance.now)")

;; Send custom nREPL messages
(nrepl/message dev/*shadow-connection* 
               {:op "eval" :code "js/document.title"})
```

### Multi-Expression Evaluation
```clojure
(dmcp/cljs! "
(def debug-data {:timestamp (js/Date.now)
                 :title js/document.title
                 :store-keys (keys @@evolver.core/store)})
(js/console.log \"Debug:\" debug-data)
debug-data")
```

## Integration with Chrome DevTools

When browser is open at http://localhost:8080, ClojureScript evaluation automatically appears in:
- **Console tab**: `js/console.log` output
- **Sources tab**: Hot reload changes
- **Application tab**: LocalStorage/DOM modifications

Use Chrome DevTools for visual verification of programmatic changes made via MCP.

## Performance Considerations

- **Connection reuse**: Single nREPL connection handles multiple evaluations
- **Session persistence**: ClojureScript context maintained across calls
- **Hot reload preserved**: Shadow-cljs development workflow unaffected
- **Memory impact**: Minimal - leverages existing shadow-cljs infrastructure

## Troubleshooting Checklist

1. **Shadow-cljs running?** `ps aux | grep shadow`
2. **Browser connected?** Visit http://localhost:8080
3. **nREPL responsive?** `lsof -i :55449`
4. **Context correct?** Try `(dmcp/cljs! "js/window")`
5. **App loaded?** Check browser console for errors

## Future Enhancements

- [ ] Auto-detect shadow-cljs nREPL port
- [ ] Health monitoring with auto-reconnection
- [ ] Support for multiple shadow-cljs builds
- [ ] Integration with Chrome DevTools MCP
- [ ] Error-specific recovery suggestions

## Success Validation

Run this test sequence to verify complete integration:

```clojure
(require '[dev-mcp :as dmcp])
(dmcp/init!)
(dmcp/cljs! "(+ 1 2 3)")  ; → 6
(dmcp/cljs! "js/document.title")  ; → "Evolver"  
(dmcp/cljs! "(js/console.log \"✅ Integration working!\")")
(dmcp/cljs! "(keys @@evolver.core/store)")  ; → (:past :present :future :view)
```

If all four evaluations succeed, ClojureScript browser REPL integration is fully functional.

---

## Summary

This solution provides **zero-friction ClojureScript evaluation** from MCP by:
1. **Respecting process boundaries** via nREPL client pattern
2. **Automating complex setup** with one-command initialization  
3. **Handling context switching** transparently
4. **Providing clear error recovery** for common failure modes

The result: MCP agents can seamlessly develop, test, and debug ClojureScript applications in live browser context without architectural workarounds or manual setup steps.