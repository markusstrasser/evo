#!/usr/bin/env bash
# Example: Quick query on a small project

set -euo pipefail

cd "$(dirname "$0")/.."

echo "=== Example: Quick Query on Small Project ==="
echo ""
echo "Querying 'malli' project for schema composition patterns"
echo ""

# List the project first
./run.sh info malli

echo ""
echo "Running query..."
echo ""

# Quick query - malli is small, so we can include full src
./run.sh explore malli \\
    "How does malli handle schema composition? Show examples of combining schemas and the rationale for the approach." \\
    --model gemini

echo ""
echo "✓ Query complete"
