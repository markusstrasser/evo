# Agent Knowledge Base

## Testing Infrastructure

### Test Commands (Bulletproof)
- **All Tests**: `npm test` → robust pipeline with validation
- **Node Only**: `npm run test:node` → pure logic tests  
- **Browser Tests**: `npm run test:ui` → compiles `:test-ui`, opens localhost:8080/test.html
- **Environment Check**: `npm run validate-env` → fail-fast validation
- **View Results**: `npm run test:results` → comprehensive summary

### File Locations (Project-Local)
- **Node test results**: `./test-results/latest-node-results.txt`
- **UI test setup**: `./test-results/latest-ui-results.txt`  
- **All logs**: `./logs/`
- **Browser test UI**: `http://localhost:8080/test.html`

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