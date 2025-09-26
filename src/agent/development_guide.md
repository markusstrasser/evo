# Development Environment Management

## Quick Environment Check

### Command Line
```bash
# Check for process conflicts and environment issues
npm run check-env

# Safe shadow-cljs wrapper (includes conflict detection)
npm run shadow-cljs watch frontend
```

### ClojureScript REPL
```clojure
;; Load agent tools
(require '[agent.core :as agent] :reload)

;; Quick environment check
(agent/detect-environment)
;; => {:browser? true, :node? false, :store-accessible? true, :cljs-repl? false}

;; Comprehensive health check
(agent/check-development-environment)
;; => {:environment {...}
;;     :issues []
;;     :healthy? true
;;     :recommendations ["Environment looks healthy" "Ready for development"]}
```

## Common Environment Issues & Solutions

### 🚨 Multiple Shadow-cljs Processes
**Symptoms**: Compilation conflicts, preload not loading, hot reload broken
**Detection**: `npm run check-env` shows multiple processes
**Solution**: 
```bash
npm run clean  # Full reset
npm dev        # Use recommended setup
```

### 🔧 Browser Not Connected  
**Symptoms**: ClojureScript REPL errors, store not accessible
**Detection**: `agent/detect-environment` shows `:browser? false`
**Solution**: Open http://localhost:8080 before connecting REPL

### ⚠️ Store Not Accessible
**Symptoms**: Agent tools fail, store operations error
**Detection**: `agent/detect-environment` shows `:store-accessible? false`  
**Solution**: Ensure app is fully loaded, check browser console for errors

## Development Workflow

1. **Start Development Environment**:
   ```bash
   npm dev  # Starts shadow-cljs + nREPL
   ```

2. **Open Browser**: http://localhost:8080

3. **Connect ClojureScript REPL**: Use MCP tools or manual connection

4. **Verify Environment**:
   ```clojure
   (agent/check-development-environment)
   ```

5. **Begin Development**: All agent tools and validation available

## Process Conflict Prevention

The enhanced `npm dev` script now includes automatic process conflict detection:

- ✅ **Prevents**: Starting development when conflicts exist
- ✅ **Detects**: Multiple shadow-cljs processes running
- ✅ **Suggests**: Concrete solutions for resolution
- ✅ **Validates**: Environment health before proceeding

This eliminates the primary cause of compilation/preload issues encountered during development.