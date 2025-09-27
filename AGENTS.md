# Agent Knowledge Base

## Testing Infrastructure

### Test Types & Commands
- **Node Tests**: `npm test` → outputs to `/tmp/evo-latest-test-results.txt`
- **Browser Tests**: `npm run test:ui` → compiles `:test-ui` build, opens `localhost:8080/test.html`
- **All Results**: `npm run test:results` → comprehensive view of all test outputs

### Critical Patterns
- Always use `npx shadow-cljs` in scripts (not bare `shadow-cljs`)
- Intent functions return transactions `[{:op :update-view :path [...] :value ...}]`, not modified state
- Browser test results: `window.getTestResultsText()` in console
- Test success pattern: grep for "0 failures, 0 errors"

### Architecture Invariants
- **shadow-cljs builds**: `:frontend` (app), `:test` (Node), `:test-ui` (browser)
- **View state updates**: Use `:update-view` command, not `:patch` (nodes only)
- **Dev server required**: Browser tests need `npm start` running
- **Temp file symlinks**: Latest results always at `/tmp/evo-latest-*-results.txt`

### Kernel Function Patterns
Selection utilities in `evolver.kernel`:
- `selected?` - check selection state
- `node-exists?` - validate node existence  
- `toggle-selection` - pure state transformation

### Intent System
- Registry: `evolver.intents/intents` map
- Transaction format: `[{:op :command-type ...}]`
- View updates: `{:op :update-view :path [:selection] :value [...]}`