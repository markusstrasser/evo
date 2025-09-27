# MCP ↔ Shadow-cljs Bridge - SUCCESS! 🎉

## Problem Solved

Successfully implemented **nREPL client bridge pattern** to overcome shadow-cljs process isolation. MCP can now:

- ✅ Evaluate ClojureScript code in browser context
- ✅ Access DOM APIs (`js/document.title` → `"Evolver"`)
- ✅ Interact with running app state
- ✅ Trigger console output in browser
- ✅ Full integration with evolver app store

## Architecture

```
MCP REPL          Shadow-cljs Server       Browser
(Port 7888)  ←→   (Port 55449 nREPL)  ←→   (WebSocket)
    ↑                     ↑                     ↑
  Your AI            Compilation             Live App
   Tools             Hot Reload              DOM APIs
```

**Key Insight**: Rather than trying to hijack shadow-cljs state from external process, we connect **as a client** to shadow-cljs's own nREPL server.

## Usage Examples

### Basic Setup
```clojure
;; 1. Start shadow-cljs
;; npm run dev

;; 2. Load and initialize bridge
(require '[dev-mcp :as dmcp])
(dmcp/init!)  ; Connects + switches to browser REPL

;; 3. Evaluate ClojureScript
(dmcp/cljs! "(js/console.log \"Hello from MCP!\")")
```

### App Integration
```clojure
;; View app state
(dmcp/cljs! "(keys @@evolver.core/store)")
;; → (:past :present :future :view)

;; Check current view
(dmcp/cljs! "(:view @@evolver.core/store)")

;; DOM manipulation
(dmcp/cljs! "js/document.title")
;; → "Evolver"

;; Browser interaction
(dmcp/cljs! "(js/alert \"MCP can control the browser!\")")
```

### Development Workflow
```clojure
;; Check connection status
(dev/status)

;; Reconnect if needed
(dev/connect-to-shadow!)

;; Switch contexts
(dev/switch-to-frontend!)  ; ClojureScript
(dmcp/cljs! ":cljs/quit")  ; Back to Clojure

;; Disconnect cleanly
(dev/disconnect-shadow!)
```

## Implementation Details

### Core Bridge Functions (`src/dev.clj`)

- `connect-to-shadow!` - Establishes nREPL client connection
- `shadow-eval` - Sends evaluation messages to shadow session
- `cljs!` - Convenient evaluation with auto-connection
- `switch-to-frontend!` - Switches to ClojureScript REPL context

### Simplified Interface (`src/dev_mcp.clj`)

- `init!` - One-command setup (connect + switch to browser)
- `cljs!` - Simple evaluation wrapper
- `help!` - Usage documentation

## Why This Works

1. **Process Separation**: Acknowledges shadow-cljs owns the JVM
2. **Client Pattern**: Connects as nREPL client, not process hijacker
3. **State Preservation**: Uses shadow's existing browser connection
4. **Context Switching**: Leverages shadow's built-in REPL switching

## Comparison to Previous Approaches

❌ **Direct API calls**: `(shadow/cljs-eval :frontend "code" {})`
- Required process ownership
- Failed with "shadow-cljs has not been started yet!"

✅ **nREPL client bridge**: `(nrepl/message client {:op "eval" :code "..."})`
- Respects process boundaries
- Uses existing shadow-cljs infrastructure
- Full browser access maintained

## Error Recovery

If connection fails:
```clojure
;; Check shadow-cljs is running
;; ps aux | grep shadow

;; Verify browser is connected
;; Open http://localhost:8080

;; Restart connection
(dev/disconnect-shadow!)
(dmcp/init!)
```

## Production Notes

- **Port dependency**: Requires shadow-cljs nREPL on port 55449
- **Browser requirement**: Needs browser open for DOM access
- **Session isolation**: Each connection creates separate nREPL session
- **Hot reload preserved**: Shadow-cljs hot reload continues working

## Future Enhancements

- [ ] Auto-detect shadow-cljs nREPL port
- [ ] Error handling for lost connections
- [ ] Support for multiple shadow-cljs builds
- [ ] Integration with Chrome DevTools MCP

---

## Success Metrics

✅ **Basic Math**: `(+ 1 2 3)` → `6`
✅ **DOM Access**: `js/document.title` → `"Evolver"`
✅ **App State**: `(keys @@evolver.core/store)` → `(:past :present :future :view)`
✅ **Console Output**: `(js/console.log "MCP works!")` → Browser console
✅ **Seamless Integration**: Can switch between Clojure/ClojureScript contexts

## Bottom Line

The "process isolation challenge" is **SOLVED**. MCP now has full ClojureScript REPL access through the nREPL client bridge pattern. This provides the foundation for all ClojureScript development workflows within the MCP environment.

**Time to development**: ~2 hours
**Architecture insight**: Don't fight shadow-cljs ownership, join it as a client
**Result**: 100% functional ClojureScript REPL integration