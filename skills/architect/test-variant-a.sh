#!/usr/bin/env bash
# Test script for Variant A specialized prompts
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Testing Variant A - Specialized Prompts"
echo "========================================="
echo

# Test description
DESCRIPTION="How should we implement undo/redo for a text editor?"

echo "Running baseline (control)..."
python3 <<EOF
import sys
sys.path.insert(0, '$SCRIPT_DIR/lib')
import architect

result = architect.propose(
    description='$DESCRIPTION',
    prompt_variant='baseline',
    verbose=True
)

print()
print(f"Baseline run ID: {result['run_id']}")
print(f"Generated {len(result['proposals'])} proposals")
EOF

echo
echo "--------------------------------------------------------------"
echo

echo "Running Variant A (specialized)..."
python3 <<EOF
import sys
sys.path.insert(0, '$SCRIPT_DIR/lib')
import architect

result = architect.propose(
    description='$DESCRIPTION',
    prompt_variant='variant-a',
    verbose=True
)

print()
print(f"Variant A run ID: {result['run_id']}")
print(f"Generated {len(result['proposals'])} proposals")

# Show preview of each proposal
for p in result['proposals']:
    provider = p['provider']
    content_preview = p['content'][:200] + "..." if len(p['content']) > 200 else p['content']
    print()
    print(f"Provider: {provider}")
    print(f"Preview: {content_preview}")
    print()
EOF

echo
echo "========================================="
echo "Test complete!"
echo
echo "Check .architect/review-runs/ for full proposals"
