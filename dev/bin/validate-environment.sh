#!/bin/bash

# Systematic environment validation to prevent common failure modes
# Run this before any development operations

set -e

VALIDATION_LOG="./logs/env-validation-$(date +%s).log"
echo "🔍 Environment Validation $(date)" | tee "$VALIDATION_LOG"
echo "=================================" | tee -a "$VALIDATION_LOG"

# Function to check and report
check_requirement() {
    local name="$1"
    local command="$2"
    local fix_hint="$3"
    
    if eval "$command" >/dev/null 2>&1; then
        echo "✅ $name" | tee -a "$VALIDATION_LOG"
        return 0
    else
        echo "❌ $name - $fix_hint" | tee -a "$VALIDATION_LOG"
        return 1
    fi
}

# Check basic tools
check_requirement "Node.js" "node --version" "Install Node.js"
check_requirement "NPM" "npm --version" "Install npm"
check_requirement "Clojure CLI" "clojure --version" "Install Clojure CLI"
check_requirement "Java" "java -version" "Install Java"

# Check shadow-cljs availability
check_requirement "shadow-cljs (local)" "npx shadow-cljs --version" "Run: npm install"

# Check project dependencies
check_requirement "NPM dependencies" "test -d node_modules" "Run: npm install"

# Check for running processes
check_requirement "Shadow-cljs server" "curl -s --connect-timeout 2 http://localhost:55449 || nc -z localhost 55449" "Run: npm start"
check_requirement "Dev server" "curl -s --connect-timeout 2 http://localhost:8080" "Run: npm start"

# Check file permissions
check_requirement "Script permissions" "test -x scripts/test-with-output.sh" "Run: chmod +x scripts/*.sh"

# Check directory structure
check_requirement "Test results dir" "test -d test-results" "Auto-created"
check_requirement "Logs dir" "test -d logs" "Auto-created"

echo "" | tee -a "$VALIDATION_LOG"
echo "🎯 Validation complete. See $VALIDATION_LOG for details" | tee -a "$VALIDATION_LOG"

# Count failures
failures=$(grep "❌" "$VALIDATION_LOG" | wc -l)
if [ "$failures" -gt 0 ]; then
    echo "⚠️  $failures issues found. Fix them before continuing." | tee -a "$VALIDATION_LOG"
    exit 1
else
    echo "🎉 All checks passed! Environment ready." | tee -a "$VALIDATION_LOG"
    exit 0
fi