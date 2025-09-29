#!/bin/bash
# CI guardrail: ensure /core doesn't depend on /labs or /legacy

set -e

echo "🔍 Checking core isolation..."

# Check if core requires labs or legacy
if rg -l 'labs\.|legacy\.' src/core/ | rg -q '\.'; then
  echo "❌ FAILED: core modules must not require labs or legacy"
  exit 1
fi

# Check if any core file mentions labs or legacy namespaces  
if rg -n 'labs\.|legacy\.' src/core/; then
  echo "❌ FAILED: core modules must not reference labs/legacy namespaces"
  exit 1
fi

echo "✅ PASSED: core is isolated from labs and legacy"