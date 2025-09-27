#!/bin/bash

# Test runner that captures output to temp file for CLI agent access
# This allows the agent to read full test results even when they're truncated

set -e

# Create project-local output file
TEMP_FILE="./test-results/node-test-results-$(date +%s).txt"
echo "📝 Capturing test output to: $TEMP_FILE"

# Run tests and capture all output
echo "🧪 Running tests with output capture..."
echo "Test run started at: $(date)" > "$TEMP_FILE"
echo "========================================" >> "$TEMP_FILE"

# Run the actual test command and capture both stdout and stderr
(./.pre-commit-check.sh && npx shadow-cljs compile test && node out/tests.js) 2>&1 | tee -a "$TEMP_FILE"

# Store the exit code
TEST_EXIT_CODE=${PIPESTATUS[0]}

echo "" >> "$TEMP_FILE"
echo "========================================" >> "$TEMP_FILE"
echo "Test run completed at: $(date)" >> "$TEMP_FILE"
echo "Exit code: $TEST_EXIT_CODE" >> "$TEMP_FILE"

# Show where the results are stored
echo ""
echo "📁 Full test results saved to: $TEMP_FILE"
echo "💡 Agent can read this file for complete test output"

# Create a symlink to the latest results for easy access
ln -sf "$TEMP_FILE" "./test-results/latest-node-results.txt"
echo "🔗 Latest results symlink: ./test-results/latest-node-results.txt"

# Exit with the same code as the tests
exit $TEST_EXIT_CODE