#!/bin/bash
# Install git hooks from scripts/hooks/ to .git/hooks/

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOKS_SRC="$SCRIPT_DIR/hooks"
HOOKS_DEST=".git/hooks"

echo "Installing git hooks..."

# Copy all hooks from scripts/hooks/ to .git/hooks/
for hook in "$HOOKS_SRC"/*; do
    if [ -f "$hook" ]; then
        hook_name=$(basename "$hook")
        cp "$hook" "$HOOKS_DEST/$hook_name"
        chmod +x "$HOOKS_DEST/$hook_name"
        echo "✓ Installed $hook_name"
    fi
done

echo "✓ Git hooks installed successfully"
