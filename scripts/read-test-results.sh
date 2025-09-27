#!/bin/bash

# Quick script to read the latest test results
# Useful for CLI agents to get test output

LATEST_RESULTS="/tmp/evo-latest-test-results.txt"

if [ ! -f "$LATEST_RESULTS" ]; then
    echo "❌ No test results found. Run 'npm test' first."
    exit 1
fi

echo "📊 Latest test results:"
echo "======================"
cat "$LATEST_RESULTS"
echo ""
echo "📁 Results file: $LATEST_RESULTS"