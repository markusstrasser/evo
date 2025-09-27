#!/bin/bash

# Comprehensive test results reader for AI agents
# Reads all available test outputs: Node tests, UI tests, logs

echo "📊 ALL TEST RESULTS SUMMARY"
echo "============================"
echo "Generated at: $(date)"
echo ""

# 1. Node.js test results (latest)
echo "🖥️  NODE.JS TEST RESULTS"
echo "========================="
if [ -f "/tmp/evo-latest-test-results.txt" ]; then
    echo "✅ Found Node.js test results:"
    echo "------------------------------"
    cat "/tmp/evo-latest-test-results.txt"
    echo ""
    echo "Summary from Node tests:"
    grep -E "(Summary:|failures|errors)" "/tmp/evo-latest-test-results.txt" | tail -3
else
    echo "❌ No Node.js test results found. Run: npm test"
fi

echo ""
echo ""

# 2. UI test setup results
echo "🌐 UI TEST SETUP RESULTS"
echo "========================="
if [ -f "/tmp/evo-latest-ui-test-results.txt" ]; then
    echo "✅ Found UI test setup log:"
    echo "----------------------------"
    cat "/tmp/evo-latest-ui-test-results.txt"
else
    echo "❌ No UI test setup found. Run: npm run test:ui"
fi

echo ""
echo ""

# 3. UI test extraction results (if available)
echo "🤖 UI TEST EXTRACTION RESULTS"
echo "=============================="
if [ -f "/tmp/evo-latest-ui-extract.txt" ]; then
    echo "✅ Found UI test extraction log:"
    echo "--------------------------------"
    cat "/tmp/evo-latest-ui-extract.txt"
else
    echo "❌ No UI test extraction found. Run: ./scripts/extract-ui-test-results.sh"
fi

echo ""
echo ""

# 4. Test status summary
echo "🎯 OVERALL TEST STATUS"
echo "======================"

NODE_STATUS="❓ Unknown"
UI_STATUS="❓ Unknown"

if [ -f "/tmp/evo-latest-test-results.txt" ]; then
    if grep -q "0 failures, 0 errors" "/tmp/evo-latest-test-results.txt"; then
        NODE_STATUS="✅ Passing"
    else
        NODE_STATUS="❌ Failing"
    fi
fi

if [ -f "/tmp/evo-latest-ui-test-results.txt" ]; then
    if grep -q "Compilation successful" "/tmp/evo-latest-ui-test-results.txt"; then
        UI_STATUS="✅ Compiled (check browser)"
    else
        UI_STATUS="❌ Compilation failed"
    fi
fi

echo "Node.js Tests: $NODE_STATUS"
echo "UI Tests:      $UI_STATUS"
echo ""

# 5. Quick commands
echo "🔧 QUICK COMMANDS FOR AI AGENTS"
echo "================================"
echo "Re-run Node tests:     npm test"
echo "Re-run UI tests:       npm run test:ui"
echo "Extract UI results:    ./scripts/extract-ui-test-results.sh"
echo "Read this summary:     ./scripts/read-all-test-results.sh"
echo ""
echo "Direct file access:"
echo "Node results:     /tmp/evo-latest-test-results.txt"
echo "UI setup log:     /tmp/evo-latest-ui-test-results.txt" 
echo "UI extraction:    /tmp/evo-latest-ui-extract.txt"
echo ""
echo "Browser access:"
echo "Test page:        http://localhost:8080/test.html"
echo "Console command:  window.getTestResultsText()"