#!/bin/bash
# Health check for development environment

set -e

echo "🔍 Development Environment Health Check"

# Check shadow-cljs server
if ! nc -z localhost 9000 2>/dev/null; then
    echo "❌ Shadow-cljs server not running on port 9000"
    echo "   Run: npm run dev"
    exit 1
else
    echo "✓ Shadow-cljs server running"
fi

# Check JVM REPL
if ! timeout 5s clj -e "(+ 1 1)" >/dev/null 2>&1; then
    echo "❌ JVM REPL unhealthy"
    exit 1
else
    echo "✓ JVM REPL responsive"
fi

# Check CLJS compilation
if ! npx shadow-cljs compile test --quiet 2>/dev/null; then
    echo "❌ CLJS compilation failed"
    exit 1
else
    echo "✓ CLJS compilation working"
fi

echo "✅ All checks passed"
