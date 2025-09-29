#!/bin/bash
# Enhanced pre-commit checks

set -e

echo "🔍 Pre-commit checks..."

# Original checks (linting, compilation, tests)
echo "📝 Running linter..."
clj-kondo --lint src test --fail-level error

echo "🔨 Compiling..."
npx shadow-cljs compile test --quiet

echo "🧪 Running tests..."
npm test --silent

# New CLJS import checks
echo "🔍 Checking for common CLJS import issues..."
if grep -r "gstr/format" src/ test/ 2>/dev/null && ! grep -r "goog.string.format" src/ test/ 2>/dev/null; then
    echo "❌ Found gstr/format usage without goog.string.format import"
    echo "   Add: #?(:cljs [goog.string.format]) to namespace requires"
    exit 1
fi

# Check for shadow-cljs dev server
if ! nc -z localhost 9000 2>/dev/null; then
    echo "⚠️  Shadow-cljs server not running - starting it..."
    echo "   After commit, run: npm run dev"
fi

echo "✅ All pre-commit checks passed"
