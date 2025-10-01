#!/bin/bash
# Evolver Development Environment Setup Script

set -e

echo "🚀 Setting up Evolver development environment..."

# Check prerequisites
echo "🔍 Checking prerequisites..."

# Check if npm is available
if ! command -v npm &> /dev/null; then
    echo "❌ npm not found. Please install Node.js"
    exit 1
fi

# Check if clojure is available
if ! command -v clojure &> /dev/null; then
    echo "❌ clojure CLI not found. Please install Clojure CLI tools"
    exit 1
fi

# Check if we're in the right directory
if [ ! -f "shadow-cljs.edn" ]; then
    echo "❌ Not in Evolver project directory (no shadow-cljs.edn found)"
    echo "   Please run from the project root"
    exit 1
fi

echo "✅ Prerequisites check passed"

# Kill any existing shadow-cljs processes to avoid conflicts
echo "🧹 Cleaning up existing processes..."
pkill -f "shadow-cljs" || true
sleep 2

# Install dependencies if needed
if [ ! -d "node_modules" ]; then
    echo "📦 Installing npm dependencies..."
    npm install
fi

# Start shadow-cljs in background
echo "🔧 Starting shadow-cljs..."
npm run dev &
SHADOW_PID=$!

# Wait for shadow-cljs to start
echo "⏳ Waiting for shadow-cljs to initialize..."
for i in {1..30}; do
    if curl -s http://localhost:8080 > /dev/null 2>&1; then
        echo "✅ Shadow-cljs server ready at http://localhost:8080"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ Shadow-cljs failed to start within 30 seconds"
        kill $SHADOW_PID || true
        exit 1
    fi
    sleep 1
done

# Wait for nREPL to be available
echo "⏳ Waiting for nREPL connection..."
for i in {1..20}; do
    if lsof -i :55449 > /dev/null 2>&1; then
        echo "✅ nREPL ready on port 55449"
        break
    fi
    if [ $i -eq 20 ]; then
        echo "❌ nREPL failed to start within 20 seconds"
        kill $SHADOW_PID || true
        exit 1
    fi
    sleep 1
done

# Open browser automatically
echo "🌐 Opening browser..."
if command -v open &> /dev/null; then
    open http://localhost:8080
elif command -v xdg-open &> /dev/null; then
    xdg-open http://localhost:8080
else
    echo "⚠️  Please manually open http://localhost:8080 in your browser"
fi

echo "
🎉 Development environment ready!

Shadow-cljs: http://localhost:8080
nREPL: port 55449

Next steps:
1. Connect your Clojure REPL to port 7888
2. Run: (require '[dev-unified :as dev])
3. Run: (dev/init!)

To stop the environment:
  pkill -f shadow-cljs

Happy coding! 🚀
"

# Keep the script running so shadow-cljs stays alive
wait $SHADOW_PID