# Systematic Error Elimination Strategy

## Category 1: Path & File Access Errors
**Root Cause**: Mixed local/temp file usage with tool restrictions

### Systematic Solution: Project-Local Everything
```bash
mkdir -p test-results logs
# Replace all /tmp/ usage with ./test-results/
# Replace all logs with ./logs/
```

**Eliminates**:
- Path validation errors
- Cross-platform temp directory issues  
- Permission problems
- File cleanup confusion

## Category 2: Environment Dependency Errors
**Root Cause**: Assumptions about system state, installed tools, running processes

### Systematic Solution: Environment Validation Layer
```clojure
;; Add to all scripts:
(defn validate-environment! []
  (assert-server-running!)
  (assert-shadow-cljs-available!)
  (assert-npm-dependencies!)
  (assert-ports-available!))
```

**Eliminates**:
- "Command not found" errors
- "Server not running" failures
- Port conflicts
- Missing dependency surprises

## Category 3: Architecture Mismatch Errors  
**Root Cause**: Confusion between Node/Browser, State/Transaction patterns

### Systematic Solution: Type-Safe Architecture Boundaries
```clojure
;; Clear separation:
(ns test.node-only ...)    ; Node.js APIs only
(ns test.browser-only ...) ; Browser APIs only  
(ns test.universal ...)    ; Pure ClojureScript

;; Intent pattern enforcement:
(defn validate-intent-return [result]
  (assert (vector? result) "Intents must return transaction vectors"))
```

**Eliminates**:
- Cross-environment API usage
- State vs transaction confusion
- Build target mismatches

## Category 4: Async/Timing Errors
**Root Cause**: Race conditions in compilation, server startup, test execution

### Systematic Solution: Robust State Polling
```bash
# Replace all sleep/timeout with:
wait-for-condition() {
  local check_cmd="$1"
  local timeout="$2"
  until eval "$check_cmd" || [ $timeout -le 0 ]; do
    sleep 1; ((timeout--))
  done
}
```

**Eliminates**:
- Fixed sleep timing issues  
- Race condition failures
- Platform-specific timing differences

## Category 5: Output Truncation/Loss
**Root Cause**: Terminal limitations, async execution

### Systematic Solution: Structured Output Pipeline
```bash
# All commands use:
cmd 2>&1 | tee -a "./logs/$(date +%s)-$operation.log" | tail -50
# Shows recent output + saves everything
```

**Eliminates**:
- Lost error messages
- Truncated test output
- Debugging information loss

## Implementation Priority

### Phase 1: Project-Local Files (High Impact, Low Risk)
- Move all temp files to `./test-results/`
- Move all logs to `./logs/`  
- Update all scripts and tools

### Phase 2: Environment Validation (Medium Impact, Medium Risk)
- Add validation functions to all entry points
- Create environment setup/health check script
- Fail fast with clear error messages

### Phase 3: Architecture Boundaries (Low Impact, High Value)
- Add type checking for intent returns
- Separate test namespaces by environment
- Schema validation for transactions

### Phase 4: Robust Polling (High Value, Medium Complexity)
- Replace all fixed delays with condition polling
- Add timeout and retry logic
- Cross-platform compatibility

## Meta-Pattern: Fail Fast & Clear

Instead of silent failures → explicit validation at boundaries:
- Entry points validate environment
- Functions validate inputs/outputs  
- Scripts validate prerequisites
- Tests validate assumptions

This deletes the "mysterious failure" category entirely.