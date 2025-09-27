#!/bin/bash

# Extract UI test results from browser for AI agent access
# Uses headless browser automation to get test results programmatically

TEMP_FILE="/tmp/evo-ui-test-extract-$(date +%s).txt"
TEST_URL="http://localhost:8080/test.html"

echo "🤖 Extracting UI test results programmatically..."
echo "📝 Output file: $TEMP_FILE"

# Check if server is running
if ! curl -s --connect-timeout 3 "$TEST_URL" > /dev/null 2>&1; then
    echo "❌ Server not running. Run 'npm start' first." | tee "$TEMP_FILE"
    exit 1
fi

# Method 1: Try using curl to check if tests have run and results are embedded
echo "🔍 Checking for embedded test results..." | tee -a "$TEMP_FILE"
if curl -s "$TEST_URL" | grep -q "window.testResults"; then
    echo "✅ Test page loaded successfully" | tee -a "$TEMP_FILE"
else
    echo "⚠️  Test framework may not be loaded yet" | tee -a "$TEMP_FILE"
fi

# Method 2: Instructions for manual extraction
echo "" | tee -a "$TEMP_FILE"
echo "📊 How to extract test results:" | tee -a "$TEMP_FILE"
echo "===============================" | tee -a "$TEMP_FILE"
echo "1. Open browser to: $TEST_URL" | tee -a "$TEMP_FILE"
echo "2. Open Developer Console (F12)" | tee -a "$TEMP_FILE"
echo "3. Wait for tests to run (or click 'Run All Tests')" | tee -a "$TEMP_FILE"
echo "4. In console, run: window.getTestResultsText()" | tee -a "$TEMP_FILE"
echo "5. Copy the output for analysis" | tee -a "$TEMP_FILE"
echo "" | tee -a "$TEMP_FILE"
echo "🔧 Quick commands for console:" | tee -a "$TEMP_FILE"
echo "   window.getTestResultsText()    // Get full results" | tee -a "$TEMP_FILE"
echo "   window.getLastTestSummary()    // Get summary only" | tee -a "$TEMP_FILE"
echo "   runAllTests()                  // Re-run tests" | tee -a "$TEMP_FILE"
echo "" | tee -a "$TEMP_FILE"

# Method 3: If we have playwright or puppeteer, use it
if command -v npx >/dev/null 2>&1 && npx playwright --version >/dev/null 2>&1; then
    echo "🎭 Using Playwright to extract results..." | tee -a "$TEMP_FILE"
    # Create a simple playwright script
    cat > /tmp/extract-results.js << 'EOF'
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  
  try {
    await page.goto('http://localhost:8080/test.html');
    await page.waitForTimeout(5000); // Wait for tests to run
    
    // Check if tests have run
    const hasResults = await page.evaluate(() => {
      return window.testResults && window.testResults.length > 0;
    });
    
    if (hasResults) {
      const results = await page.evaluate(() => window.getTestResultsText());
      const summary = await page.evaluate(() => window.getLastTestSummary());
      
      console.log('✅ Test Results Extracted:');
      console.log('===========================');
      console.log(results);
      console.log('');
      console.log('📊 Summary:', summary);
    } else {
      console.log('⚠️  No test results found. Tests may not have run yet.');
    }
  } catch (error) {
    console.error('❌ Error extracting results:', error.message);
  } finally {
    await browser.close();
  }
})();
EOF
    
    if npx playwright install chromium 2>/dev/null && node /tmp/extract-results.js | tee -a "$TEMP_FILE"; then
        echo "✅ Playwright extraction successful" | tee -a "$TEMP_FILE"
    else
        echo "⚠️  Playwright extraction failed" | tee -a "$TEMP_FILE"
    fi
    rm -f /tmp/extract-results.js
else
    echo "💡 Install Playwright for automated extraction: npm install -g playwright" | tee -a "$TEMP_FILE"
fi

# Create symlink
ln -sf "$TEMP_FILE" "/tmp/evo-latest-ui-extract.txt"

echo ""
echo "📁 Extraction log: $TEMP_FILE"
echo "🔗 Symlink: /tmp/evo-latest-ui-extract.txt"
echo "🌐 Test page: $TEST_URL"