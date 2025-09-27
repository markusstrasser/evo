#!/bin/bash

# Robust test runner that eliminates common failure categories
# Uses systematic validation and condition waiting

set -e

source "./scripts/wait-for-condition.sh"

LOG_FILE="./logs/test-run-$(date +%s).log"
echo "🚀 Robust Test Runner $(date)" | tee "$LOG_FILE"
echo "================================" | tee -a "$LOG_FILE"

# Phase 1: Environment validation
echo "📋 Phase 1: Environment Validation" | tee -a "$LOG_FILE"
if ! ./scripts/validate-environment.sh >> "$LOG_FILE" 2>&1; then
    echo "❌ Environment validation failed. See $LOG_FILE" | tee -a "$LOG_FILE"
    exit 1
fi

# Phase 2: Ensure services are running
echo "📋 Phase 2: Service Availability" | tee -a "$LOG_FILE"
if ! wait_for_server "http://localhost:8080" 10; then
    echo "⚠️  Dev server not running. Starting services..." | tee -a "$LOG_FILE"
    echo "   Please run 'npm start' in another terminal and re-run this script" | tee -a "$LOG_FILE"
    exit 1
fi

# Phase 3: Run Node tests with robust output capture
echo "📋 Phase 3: Node.js Tests" | tee -a "$LOG_FILE"
NODE_RESULTS="./test-results/node-test-results-$(date +%s).txt"
if (./.pre-commit-check.sh && npx shadow-cljs compile test && node out/tests.js) 2>&1 | tee "$NODE_RESULTS"; then
    ln -sf "$NODE_RESULTS" "./test-results/latest-node-results.txt"
    echo "✅ Node tests completed: $NODE_RESULTS" | tee -a "$LOG_FILE"
else
    echo "❌ Node tests failed. See: $NODE_RESULTS" | tee -a "$LOG_FILE"
    exit 1
fi

# Phase 4: Compile and prepare UI tests  
echo "📋 Phase 4: UI Test Compilation" | tee -a "$LOG_FILE"
UI_RESULTS="./test-results/ui-test-results-$(date +%s).txt"
if npx shadow-cljs compile test-ui 2>&1 | tee "$UI_RESULTS"; then
    ln -sf "$UI_RESULTS" "./test-results/latest-ui-results.txt"
    echo "✅ UI tests compiled: $UI_RESULTS" | tee -a "$LOG_FILE"
    echo "🌐 Access UI tests at: http://localhost:8080/test.html" | tee -a "$LOG_FILE"
else
    echo "❌ UI test compilation failed. See: $UI_RESULTS" | tee -a "$LOG_FILE"
    exit 1
fi

# Phase 5: Summary and next steps
echo "📋 Phase 5: Summary" | tee -a "$LOG_FILE"
echo "🎉 All automated tests completed successfully!" | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"
echo "📊 Results:" | tee -a "$LOG_FILE"
echo "   Node.js tests: ./test-results/latest-node-results.txt" | tee -a "$LOG_FILE"
echo "   UI test setup: ./test-results/latest-ui-results.txt" | tee -a "$LOG_FILE"
echo "   Full run log:  $LOG_FILE" | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"
echo "🎯 For UI test results:" | tee -a "$LOG_FILE"
echo "   1. Visit: http://localhost:8080/test.html" | tee -a "$LOG_FILE"
echo "   2. Console: window.getTestResultsText()" | tee -a "$LOG_FILE"
echo "   3. Or use: npm run test:extract-ui" | tee -a "$LOG_FILE"