#!/bin/bash
# Shadow-cljs Process Conflict Detector
# Run this before starting any shadow-cljs commands

echo "🔍 Checking for running shadow-cljs processes..."

NPM_DEV=$(ps aux | grep -v grep | grep "npm.*dev" | wc -l | tr -d ' ')
SHADOW_WATCH=$(ps aux | grep -v grep | grep "shadow-cljs.*watch" | wc -l | tr -d ' ')
PORT_8080=$(lsof -i :8080 2>/dev/null | wc -l | tr -d ' ')

echo "📊 Process Status:"
echo "  npm dev processes: $NPM_DEV"
echo "  shadow-cljs watch processes: $SHADOW_WATCH"  
echo "  Port 8080 in use: $PORT_8080"

# Check for dangerous conflicts
if [ "$NPM_DEV" -gt 0 ] && [ "$SHADOW_WATCH" -gt 1 ]; then
    echo ""
    echo "🚨 CRITICAL: Multiple shadow-cljs processes detected!"
    echo "   This causes preload/compilation conflicts"
    echo "   Current processes:"
    ps aux | grep -v grep | grep -E "(npm.*dev|shadow-cljs.*watch)"
    echo ""
    echo "🔧 Solution:"
    echo "   1. Stop manual shadow-cljs: pkill -f 'shadow-cljs.*watch'"
    echo "   2. Use 'npm dev' for development"
    echo "   3. Or run 'npm run clean' for full reset"
    exit 1
fi

# Normal operation modes
if [ "$NPM_DEV" -gt 0 ]; then
    echo "✅ npm dev is running (recommended mode)"
    echo "💡 Connect ClojureScript REPL or use browser console"
elif [ "$SHADOW_WATCH" -gt 0 ]; then
    echo "✅ Manual shadow-cljs watch running"
    echo "💡 Consider using 'npm dev' for nREPL + shadow-cljs"
else
    echo "ℹ️  No shadow-cljs processes detected"
    echo "💡 Start with 'npm dev' for full development environment"
fi

echo ""