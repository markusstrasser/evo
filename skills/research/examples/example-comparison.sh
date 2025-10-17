#!/usr/bin/env bash
# Example: Compare reactive state patterns across projects

set -euo pipefail

cd "$(dirname "$0")/.."

echo "=== Example: Cross-Project Pattern Comparison ==="
echo ""
echo "Comparing reactive state management in re-frame vs electric"
echo ""

./run.sh compare "re-frame,electric" \\
    "reactive state management and subscription patterns" \\
    --model codex

echo ""
echo "✓ Comparison complete"
