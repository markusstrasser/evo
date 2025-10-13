#!/usr/bin/env bash
# Quick evaluator test with mock judge (<10s)
# Tests pipeline end-to-end without burning API credits

set -e

echo "⚡ Quick Eval - Testing with mock judge"
echo "======================================="
echo ""

# Run with verbose output for debugging
clojure -M run_eval_verbose.clj

echo ""
echo "✅ Quick eval complete!"
