#!/bin/bash

# UI Test runner that captures browser test output for CLI agent access
# This compiles ClojureScript tests for the browser and provides multiple ways to access results

set -e

# Create temp file for output  
TEMP_FILE="/tmp/evo-ui-test-results-$(date +%s).txt"
TEST_URL="http://localhost:8080/test.html"

echo "📝 Capturing UI test output to: $TEMP_FILE"
echo "🌐 Test URL: $TEST_URL"

# Start output capture
echo "UI Test run started at: $(date)" > "$TEMP_FILE"
echo "Test URL: $TEST_URL" >> "$TEMP_FILE" 
echo "========================================" >> "$TEMP_FILE"

echo "🧪 Running UI tests with browser compilation..."

# Step 1: Compile the UI test build
echo "📦 Compiling ClojureScript tests for browser..." | tee -a "$TEMP_FILE"
if npx shadow-cljs compile test-ui 2>&1 | tee -a "$TEMP_FILE"; then
    echo "✅ Compilation successful" | tee -a "$TEMP_FILE"
else
    echo "❌ Compilation failed" | tee -a "$TEMP_FILE"
    exit 1
fi

# Step 2: Check if server is running
echo "🌐 Checking if development server is running..." | tee -a "$TEMP_FILE"
if curl -s --connect-timeout 3 "$TEST_URL" > /dev/null 2>&1; then
    echo "✅ Server is running at $TEST_URL" | tee -a "$TEMP_FILE"
else
    echo "❌ Server not running. Please start with: npm run start" | tee -a "$TEMP_FILE"
    echo "   Then you can access tests at: $TEST_URL" | tee -a "$TEMP_FILE"
    exit 1
fi

# Step 3: Give instructions for running tests
echo "" | tee -a "$TEMP_FILE"
echo "🎯 UI Tests Ready!" | tee -a "$TEMP_FILE"
echo "===================" | tee -a "$TEMP_FILE"
echo "1. Open browser to: $TEST_URL" | tee -a "$TEMP_FILE"
echo "2. Tests will auto-run when page loads" | tee -a "$TEMP_FILE"
echo "3. Click 'Run All Tests' to re-run" | tee -a "$TEMP_FILE"
echo "" | tee -a "$TEMP_FILE"
echo "📊 AI Agent Access Methods:" | tee -a "$TEMP_FILE"
echo "   - Browser console: window.getTestResultsText()" | tee -a "$TEMP_FILE"
echo "   - Browser console: window.getLastTestSummary()" | tee -a "$TEMP_FILE"
echo "   - Download button on test page" | tee -a "$TEMP_FILE"
echo "" | tee -a "$TEMP_FILE"

# Step 4: Try to open browser automatically (if possible)
if command -v open >/dev/null 2>&1; then
    echo "🚀 Opening browser automatically..." | tee -a "$TEMP_FILE"
    open "$TEST_URL"
elif command -v xdg-open >/dev/null 2>&1; then
    echo "🚀 Opening browser automatically..." | tee -a "$TEMP_FILE"
    xdg-open "$TEST_URL"
else
    echo "💡 Please manually open: $TEST_URL" | tee -a "$TEMP_FILE"
fi

# Step 5: Wait a bit and try to extract results if browser supports it
echo "⏳ Waiting for tests to potentially run..." | tee -a "$TEMP_FILE"
sleep 5

echo "" >> "$TEMP_FILE"
echo "========================================" >> "$TEMP_FILE"
echo "UI test setup completed at: $(date)" >> "$TEMP_FILE"

# Create symlinks for easy access
ln -sf "$TEMP_FILE" "/tmp/evo-latest-ui-test-results.txt"
echo "🔗 Latest UI test results: /tmp/evo-latest-ui-test-results.txt"

echo ""
echo "📁 Full setup log saved to: $TEMP_FILE"
echo "💡 Agent can read this file, then access browser console for live results"
echo "🌐 Browser UI: $TEST_URL"