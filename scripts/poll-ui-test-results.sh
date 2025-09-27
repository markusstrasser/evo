#!/bin/bash

# Poll for UI test results from the browser
# Uses curl to check for results embedded in the page after tests run

TEMP_FILE="/tmp/evo-ui-poll-results-$(date +%s).txt"
TEST_URL="http://localhost:8080/test.html"
MAX_ATTEMPTS=30
SLEEP_INTERVAL=2

echo "🔄 Polling for UI test results..." | tee "$TEMP_FILE"
echo "📍 Test URL: $TEST_URL" | tee -a "$TEMP_FILE"
echo "⏱️  Max attempts: $MAX_ATTEMPTS (${SLEEP_INTERVAL}s intervals)" | tee -a "$TEMP_FILE"
echo "===========================================" | tee -a "$TEMP_FILE"

# Check if server is running
if ! curl -s --connect-timeout 3 "$TEST_URL" > /dev/null 2>&1; then
    echo "❌ Server not running. Please start with: npm start" | tee -a "$TEMP_FILE"
    exit 1
fi

# Function to extract test results from page HTML
extract_results() {
    local page_content=$(curl -s "$TEST_URL" 2>/dev/null)
    
    # Look for test results in the page
    if echo "$page_content" | grep -q "window.testResults"; then
        echo "✅ Test framework detected in page"
        return 0
    else
        echo "⏳ Test framework not yet loaded"
        return 1
    fi
}

# Function to check if tests have completed by looking for summary
check_test_completion() {
    # Use a simple JavaScript evaluation if possible
    # For now, we'll simulate this by checking page updates
    local page_content=$(curl -s "$TEST_URL" 2>/dev/null)
    
    # Look for indicators that tests have run
    if echo "$page_content" | grep -q "Test framework loaded"; then
        return 0
    else
        return 1
    fi
}

echo "🚀 Starting to poll for results..." | tee -a "$TEMP_FILE"
echo "" | tee -a "$TEMP_FILE"

for i in $(seq 1 $MAX_ATTEMPTS); do
    echo "Attempt $i/$MAX_ATTEMPTS..." | tee -a "$TEMP_FILE"
    
    if extract_results >> "$TEMP_FILE" 2>&1; then
        echo "✅ Test framework found! Tests should be running..." | tee -a "$TEMP_FILE"
        break
    fi
    
    if [ $i -eq $MAX_ATTEMPTS ]; then
        echo "❌ Timeout waiting for test framework to load" | tee -a "$TEMP_FILE"
        break
    fi
    
    sleep $SLEEP_INTERVAL
done

echo "" | tee -a "$TEMP_FILE"
echo "📋 To manually extract results:" | tee -a "$TEMP_FILE"
echo "1. Open browser console at: $TEST_URL" | tee -a "$TEMP_FILE"
echo "2. Run: window.getTestResultsText()" | tee -a "$TEMP_FILE"
echo "3. Copy the output" | tee -a "$TEMP_FILE"
echo "" | tee -a "$TEMP_FILE"
echo "🔧 Alternative: Click 'Download Results' button on test page" | tee -a "$TEMP_FILE"
echo "" | tee -a "$TEMP_FILE"

# Create symlink
ln -sf "$TEMP_FILE" "/tmp/evo-latest-ui-poll.txt"

echo "📁 Poll log saved to: $TEMP_FILE"
echo "🔗 Symlink: /tmp/evo-latest-ui-poll.txt"