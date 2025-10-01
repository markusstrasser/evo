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

# Namespace/path consistency checks
echo "🔍 Checking namespace/path consistency..."

# Check dev/ directory (classpath root: dev)
for file in $(find dev/repl -name "*.clj" -o -name "*.cljc" 2>/dev/null); do
    ns_decl=$(grep -m1 "^(ns " "$file" | sed 's/(ns //' | awk '{print $1}')
    if [[ "$ns_decl" == dev.repl.* ]]; then
        echo "❌ Wrong namespace in $file: $ns_decl"
        echo "   With 'dev' on classpath, files in dev/repl/ should use namespace 'repl.*', not 'dev.repl.*'"
        exit 1
    fi
done

# Check mcp/ directory (classpath root: mcp)
for file in $(find mcp/servers -name "*.clj" -o -name "*.cljc" 2>/dev/null); do
    ns_decl=$(grep -m1 "^(ns " "$file" | sed 's/(ns //' | awk '{print $1}')
    if [[ "$ns_decl" == mcp.servers.* ]]; then
        echo "❌ Wrong namespace in $file: $ns_decl"
        echo "   With 'mcp' on classpath, files in mcp/servers/ should use namespace 'servers.*', not 'mcp.servers.*'"
        exit 1
    fi
done

# Check for shadow-cljs dev server
if ! nc -z localhost 9000 2>/dev/null; then
    echo "⚠️  Shadow-cljs server not running - starting it..."
    echo "   After commit, run: npm run dev"
fi

echo "✅ All pre-commit checks passed"
