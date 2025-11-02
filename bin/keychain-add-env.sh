#!/bin/bash
# One-time script to migrate .env API keys to macOS Keychain
# Usage: ./bin/keychain-add-env.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "❌ .env file not found at $ENV_FILE"
  exit 1
fi

echo "🔐 Migrating API keys from .env to Keychain..."
echo ""

# Read .env and add each key to Keychain
while IFS='=' read -r key value; do
  # Skip empty lines and comments
  [[ -z "$key" || "$key" =~ ^# ]] && continue

  # Remove 'export ' prefix if present
  key="${key#export }"
  key="${key// /}"  # Remove whitespace

  # Skip if no value
  [[ -z "$value" ]] && continue

  echo "Adding $key to Keychain..."

  # Delete existing entry (ignore errors if it doesn't exist)
  security delete-generic-password -a "$USER" -s "env.$key" 2>/dev/null || true

  # Add to Keychain
  security add-generic-password \
    -a "$USER" \
    -s "env.$key" \
    -w "$value" \
    -U

  echo "✅ $key added"
done < "$ENV_FILE"

echo ""
echo "✨ Done! API keys are now in Keychain."
echo "💡 Next steps:"
echo "   1. Add this to ~/.zshrc:"
echo "      source ~/Projects/evo/bin/keychain-load-env.sh"
echo "   2. Backup and delete .env file (or add to .gitignore)"
echo "   3. Restart your shell or run: source ~/.zshrc"
