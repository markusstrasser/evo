#!/bin/bash
# Agent Environment Health Check
# Run this when things start breaking mysteriously

echo "🔍 Agent Environment Health Check"
echo "================================="

# Check for process conflicts
echo "Checking for shadow-cljs conflicts..."
if pgrep -f "shadow-cljs" > /dev/null; then
    echo "⚠️  Shadow-cljs process running. May conflict with manual commands."
    echo "   Processes: $(pgrep -f shadow-cljs | tr '\n' ' ')"
fi

if pgrep -f "npm.*dev" > /dev/null; then
    echo "⚠️  npm dev process running. Don't run manual shadow-cljs commands."
fi

# Check critical files
echo "Checking critical files..."
[ -f ".clj-kondo/config.edn" ] && echo "✅ clj-kondo config present" || echo "❌ Missing .clj-kondo/config.edn"
[ -f ".pre-commit-check.sh" ] && echo "✅ Pre-commit checks present" || echo "❌ Missing .pre-commit-check.sh"
[ -f "shadow-cljs.edn" ] && echo "✅ Shadow-cljs config present" || echo "❌ Missing shadow-cljs.edn"

# Check for common cache corruption
echo "Checking for cache issues..."
if [ -d ".shadow-cljs" ] && [ $(find .shadow-cljs -name "*.jar" | wc -l) -gt 100 ]; then
    echo "⚠️  Large cache detected. Consider 'npm run clean' if issues persist."
fi

# Check clj-kondo health
echo "Testing clj-kondo..."
if clj-kondo --lint src --fail-level error > /dev/null 2>&1; then
    echo "✅ clj-kondo working"
else
    echo "❌ clj-kondo issues detected"
fi

# Check compilation
echo "Testing compilation..."
if npx shadow-cljs compile test > /dev/null 2>&1; then
    echo "✅ Compilation working"
else
    echo "❌ Compilation failing"
fi

echo "================================="
echo "Health check complete. Fix any ❌ issues above."