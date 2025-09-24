# Agent Guidelines for Evolver ClojureScript Project

## Test Environment Failure Modes

### Primary Issues
- **Runtime Mismatch**: Tests assume browser APIs but run in node environment
- **Missing Dependencies**: `bun` not universally available, stick with `node`
- **Cache Corruption**: Shadow-cljs cache often needs cleaning before tests work
- **Build Target Confusion**: Agent namespaces may not be available in `:node-test` target

### Key Misconceptions to Avoid
1. **Don't assume `bun` exists** - Always use `node` for test execution
2. **Don't skip cache cleaning** - Run `npx shadow-cljs clean` when tests fail mysteriously
3. **Don't assume browser APIs in tests** - Node test environment lacks DOM/window
4. **Don't expect agent/* namespaces in tests** - They may not be in test build source-paths

## Quick Test Fixes

### When Tests Won't Run:
1. `npx shadow-cljs clean` 
2. Change test script to use `node` not `bun`
3. Check if test target includes needed source paths
4. Verify compilation succeeds before running output

### Test Script Pattern:
```json
"test": "shadow-cljs compile test && node out/tests.js"
```

### Common Cache Issues:
- Delete `.shadow-cljs/` and `out/` directories
- Use `npx shadow-cljs clean` command
- Restart shadow-cljs server if running

## Environment Requirements
- **Node.js**: Required for test execution
- **Browser**: Required for REPL and development  
- **Shadow-cljs**: Must be available via npx

## Test Architecture Notes
- Tests should be runnable without browser connection
- Avoid browser-specific APIs in unit tests
- Use integration tests for UI interactions via chrome-devtools
- Keep test dependencies minimal and standard