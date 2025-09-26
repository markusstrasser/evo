#!/bin/bash
set -e
echo "🔍 Pre-commit validation..."

# Check for cross-platform issues in .cljc files
CLJC_FILES=$(git diff --cached --name-only | grep -E "\.cljc$" || true)
if [ -n "$CLJC_FILES" ]; then
    if echo "$CLJC_FILES" | xargs grep -l "js/" 2>/dev/null; then
        echo "❌ Found bare js/ references in .cljc files - add reader conditionals"
        exit 1
    fi
fi

# Check for test macro hygiene
TEST_FILES=$(git diff --cached --name-only | grep -E "test.*\.cljc?$" || true)
if [ -n "$TEST_FILES" ]; then
    if echo "$TEST_FILES" | xargs grep -l "test/testing\|clojure.test/" 2>/dev/null; then
        echo "❌ Found qualified test namespace references in macros - use unqualified symbols"
        exit 1
    fi
fi

# Validate shadow-cljs compilation
echo "🔧 Validating compilation..."
if ! npx shadow-cljs compile test > /dev/null 2>&1; then
    echo "❌ Shadow-cljs compilation failed"
    npx shadow-cljs compile test  # Show errors
    exit 1
fi

echo "✅ Pre-commit checks passed"
