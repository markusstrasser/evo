#!/bin/bash

set -e

PREFLIGHT_CONFIG="dev/preflight.edn"
LOG_FILE="logs/preflight-$(date +%s).log"

mkdir -p logs

echo "🚀 Preflight Check $(date)" | tee "$LOG_FILE"
echo "================================" | tee -a "$LOG_FILE"

ISSUES=0

check() {
    local name=$1
    local desc=$2
    local cmd=$3
    local expect=$4
    local severity=$5
    
    echo -n "Checking $desc... " | tee -a "$LOG_FILE"
    
    result=$(eval "$cmd" 2>/dev/null || echo "0")
    result=$(echo "$result" | tr -d '[:space:]')
    
    if [ "$result" = "$expect" ]; then
        echo "✅" | tee -a "$LOG_FILE"
    else
        if [ "$severity" = "error" ]; then
            echo "❌ FAIL (expected: $expect, got: $result)" | tee -a "$LOG_FILE"
            ISSUES=$((ISSUES + 1))
        else
            echo "⚠️  WARN (expected: $expect, got: $result)" | tee -a "$LOG_FILE"
        fi
    fi
}

echo "" | tee -a "$LOG_FILE"
echo "Running checks from $PREFLIGHT_CONFIG..." | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"

check "shadow-conflict" \
      "No stale shadow-cljs processes" \
      "pgrep -f 'shadow-cljs' 2>/dev/null | wc -l | tr -d ' '" \
      "0" \
      "warn"

check "dev-server" \
      "Dev server running on port 8080" \
      "lsof -i :8080 2>/dev/null | grep LISTEN | wc -l | tr -d ' '" \
      "1" \
      "error"

check "nrepl-port" \
      "nREPL server running on port 7888" \
      "lsof -i :7888 2>/dev/null | grep LISTEN | wc -l | tr -d ' '" \
      "1" \
      "error"

check "cache-size" \
      "Shadow-cljs cache not bloated" \
      "du -sm .shadow-cljs 2>/dev/null | awk '{print \$1}' || echo 0" \
      "<500" \
      "warn"

echo "" | tee -a "$LOG_FILE"
echo "================================" | tee -a "$LOG_FILE"

if [ $ISSUES -eq 0 ]; then
    echo "✅ All critical checks passed!" | tee -a "$LOG_FILE"
    echo "📝 Full log: $LOG_FILE" | tee -a "$LOG_FILE"
    exit 0
else
    echo "❌ $ISSUES critical issue(s) detected" | tee -a "$LOG_FILE"
    echo "" | tee -a "$LOG_FILE"
    echo "💡 Suggested fixes:" | tee -a "$LOG_FILE"
    echo "   npm run dev          - Start dev server and nREPL" | tee -a "$LOG_FILE"
    echo "   npm run fix:cache    - Clear caches and reinstall" | tee -a "$LOG_FILE"
    echo "   npm run agent:health - Detailed diagnostics" | tee -a "$LOG_FILE"
    echo "" | tee -a "$LOG_FILE"
    echo "📝 Full log: $LOG_FILE" | tee -a "$LOG_FILE"
    exit 1
fi