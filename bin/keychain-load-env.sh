#!/bin/bash
# Source this in ~/.zshrc to load API keys from Keychain
# Usage: source ~/Projects/evo/bin/keychain-load-env.sh

# List of API keys to load from Keychain
API_KEYS=(
  "OPENAI_API_KEY"
  "GEMINI_API_KEY"
  "GROK_API_KEY"
  "XAI_API_KEY"
  "ANTHROPIC_API_KEY"
  "CLAUDE_API_KEY"
  "OPENROUTER_API_KEY"
  "EXA_API_KEY"
)

# Retrieve each key from Keychain and export it
for key in "${API_KEYS[@]}"; do
  value=$(security find-generic-password -a "$USER" -s "env.$key" -w 2>/dev/null)

  if [[ -n "$value" ]]; then
    export "$key=$value"
  else
    # Silent failure - key not in Keychain (optional: uncomment to debug)
    # echo "⚠️  $key not found in Keychain" >&2
    :
  fi
done
