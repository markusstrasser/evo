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

# Phase 4: Summary
echo "📋 Phase 4: Summary" | tee -a "$LOG_FILE"
echo "🎉 Node.js tests completed successfully!" | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"
echo "📊 Results:" | tee -a "$LOG_FILE"
echo "   Node.js tests: ./test-results/latest-node-results.txt" | tee -a "$LOG_FILE"
echo "   Full run log:  $LOG_FILE" | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"
echo "🎯 For ClojureScript/browser tests:" | tee -a "$LOG_FILE"
echo "   Use: npm run test:cljs" | tee -a "$LOG_FILE"
echo "   Or REPL: clojure -M -e '(load-file \"scripts/cljs-test-runner.clj\")'" | tee -a "$LOG_FILE"