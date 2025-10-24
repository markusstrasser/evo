#!/usr/bin/env bash
# Session Search Examples

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "=== Session Memory Search Examples ==="
echo ""

echo "1. Semantic search for conceptually similar content:"
echo "   $ bb sessions search --sem 'kernel architecture' --limit 3"
echo ""

echo "2. Lexical search for exact phrases/terms:"
echo "   $ bb sessions search --lex 'three operations' --limit 5"
echo ""

echo "3. Hybrid search (recommended):"
echo "   $ bb sessions search --hybrid 'compounding patterns' --threshold 0.7"
echo ""

echo "4. Search with similarity scores:"
echo "   $ bb sessions search --sem --scores 'event sourcing'"
echo ""

echo "5. Rebuild index after many new sessions:"
echo "   $ bb rebuild-index"
echo ""

# Uncomment to run a real example:
# echo "Running example search..."
# bb sessions search --sem "kernel IR" --limit 3
