# Agent Knowledge Base

## Project Structure & Architecture

### Core Namespaces
- **evolver.core** - Main application entry point
- **evolver.kernel** - Pure utility functions (selection, validation, transforms)
- **evolver.intents** - Command registry and transaction builders
- **evolver.dispatcher** - Command execution and state coordination
- **evolver.state** - Application state management
- **evolver.renderer** - View rendering logic

### Data Flow Patterns
- **Commands as Data**: All user actions become transaction vectors
- **Pure Functions**: Kernel utilities never mutate, always return new values
- **Event Sourcing**: State changes via transaction log
- **View Separation**: UI updates via `:update-view` commands, not direct state mutation

### File System Conventions
- **Tests**: All in `test/` directory, Node tests exclude `browser_ui.cljs`
- **Scripts**: Operational scripts in `scripts/` directory with validation
- **Results**: Project-local `./test-results/` and `./logs/` directories
- **Dev Tools**: `src/dev.clj` provides unified development environment

## Environment Dependencies

### Shadow-cljs Build Targets
- **:frontend** - Main application (localhost:8080)
- **:test** - Node.js test runner (excludes browser APIs)
- **:test-ui** - DEPRECATED (use REPL bridge instead)

### Required Services
- **shadow-cljs watch** - Must be running for browser evaluation
- **nREPL connection** - Auto-detected on ports 7888-7900
- **HTTP server** - localhost:8080 for frontend application

### MCP Tool Integration
- **clojure_eval** - Node.js Clojure evaluation
- **clojurescript_eval** - Browser ClojureScript evaluation via shadow-cljs
- **file system tools** - Project-local operations only
- **REPL bridge** - `src/dev.clj` provides unified cross-context evaluation

## Testing Infrastructure

### Test Commands (Bulletproof)
- **All Tests**: `npm test` → robust pipeline with validation
- **Node Only**: `npm run test:node` → pure logic tests  
- **ClojureScript Tests**: `npm run test:cljs` → REPL-based browser evaluation
- **Environment Check**: `npm run validate-env` → fail-fast validation
- **View Results**: `npm run test:results` → comprehensive summary

### File Locations (Project-Local)
- **Node test results**: `./test-results/latest-node-results.txt`
- **All logs**: `./logs/`
- **Application UI**: `http://localhost:8080/` (shadow-cljs :frontend build)
- **ClojureScript REPL**: Available via `src/dev.clj` integration

### Critical Patterns
- Always use `npx shadow-cljs` in scripts (not bare `shadow-cljs`)
- Intent functions return transactions `[{:op :update-view :path [...] :value ...}]`, not modified state
- Browser test results: `window.getTestResultsText()` in console
- Test success pattern: grep for "0 failures, 0 errors"
- Environment validation BEFORE any operations

### Architecture Invariants
- **shadow-cljs builds**: `:frontend` (app), `:test` (Node), `:test-ui` (browser)
- **Test separation**: Node tests exclude browser-ui-test (browser APIs)
- **View state updates**: Use `:update-view` command, not `:patch` (nodes only)
- **Dev server required**: Browser tests need `npm start` running
- **No /tmp files**: Everything in project-local directories

### Kernel Function Patterns
Selection utilities in `evolver.kernel`:
- `selected?` - check selection state
- `node-exists?` - validate node existence  
- `toggle-selection` - pure state transformation

### Intent System
- Registry: `evolver.intents/intents` map
- Transaction format: `[{:op :command-type ...}]`
- View updates: `{:op :update-view :path [:selection] :value [...]}`

### REPL Integration
- **Dev utilities**: `src/dev.clj` - unified ClojureScript REPL bridge
- **Auto-connection**: `(init!)` connects to shadow-cljs nREPL
- **Smart evaluation**: `(cljs! "code")` for browser, `(clj! "code")` for Clojure
- **Environment health**: `(preflight-check!)` validates full stack

### Error Elimination Strategies
- **Environment validation**: All scripts check prerequisites first
- **Robust waiting**: `wait-for-condition` replaces fixed sleeps
- **Project-local files**: No /tmp dependencies or permission issues
- **Fail-fast patterns**: Clear errors at every boundary
- **Structured logging**: All operations logged to `./logs/`