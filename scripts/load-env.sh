#!/usr/bin/env bash
# Load .env file if it exists
# Source this script before running LLM commands

if [ -f .env ]; then
    set -a  # automatically export all variables
    source .env
    set +a
    echo "✓ Loaded .env"
else
    echo "⚠ No .env file found"
fi
