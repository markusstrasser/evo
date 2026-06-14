#!/bin/bash
# Install git hooks from scripts/hooks/ to the repo's hooks dir.
# Runs from `npm install` via the "prepare" script, so it must no-op
# (not fail) outside a git checkout — e.g. an npm tarball install.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOKS_SRC="$SCRIPT_DIR/hooks"

# Resolve the real hooks dir. `git rev-parse --git-path hooks` handles
# worktrees (where .git is a file, not a dir) — relevant for `claude --worktree`.
HOOKS_DEST="$(git rev-parse --git-path hooks 2>/dev/null)"
if [ -z "$HOOKS_DEST" ] || [ ! -d "$(dirname "$HOOKS_DEST")" ]; then
    echo "ℹ️  Not a git checkout — skipping git hook install."
    exit 0
fi
mkdir -p "$HOOKS_DEST"

echo "Installing git hooks..."

# Copy all hooks from scripts/hooks/ to the hooks dir.
for hook in "$HOOKS_SRC"/*; do
    if [ -f "$hook" ]; then
        hook_name=$(basename "$hook")
        cp "$hook" "$HOOKS_DEST/$hook_name"
        chmod +x "$HOOKS_DEST/$hook_name"
        echo "✓ Installed $hook_name"
    fi
done

echo "✓ Git hooks installed successfully"
