#!/usr/bin/env bash
# Force-reinstall UV tool with cache clearing for development iteration
#
# Usage:
#   scripts/uv-dev-reinstall.sh <package-path>
#   scripts/uv-dev-reinstall.sh llmx
#   scripts/uv-dev-reinstall.sh /path/to/package
#
# Problem this solves:
#   UV caches Python bytecode and doesn't always pick up source changes
#   even with --force flag, causing 5+ minute iteration cycles.
#
# What it does:
#   1. Uninstalls the tool
#   2. Clears UV cache
#   3. Reinstalls from source
#   4. Verifies installation
#   5. Shows timestamps to confirm fresh install

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

error() {
    echo -e "${RED}✗${NC} $*" >&2
    exit 1
}

info() {
    echo -e "${BLUE}ℹ${NC} $*"
}

success() {
    echo -e "${GREEN}✓${NC} $*"
}

warn() {
    echo -e "${YELLOW}⚠${NC} $*"
}

# Check if package provided
if [ $# -eq 0 ]; then
    error "Usage: $0 <package-path>

Examples:
  $0 llmx                    # If llmx/ is in current directory
  $0 ~/Projects/evo/llmx     # Absolute path
  $0 ../llmx                 # Relative path"
fi

PACKAGE_ARG="$1"

# Resolve package path
if [ -d "$PACKAGE_ARG" ]; then
    PACKAGE_PATH="$(cd "$PACKAGE_ARG" && pwd)"
elif [ -d "./$PACKAGE_ARG" ]; then
    PACKAGE_PATH="$(cd "./$PACKAGE_ARG" && pwd)"
else
    error "Package not found: $PACKAGE_ARG"
fi

# Extract package name from pyproject.toml
if [ ! -f "$PACKAGE_PATH/pyproject.toml" ]; then
    error "No pyproject.toml found in $PACKAGE_PATH"
fi

PACKAGE_NAME=$(grep -E '^\s*name\s*=' "$PACKAGE_PATH/pyproject.toml" | sed -E 's/.*name\s*=\s*"([^"]+)".*/\1/')

if [ -z "$PACKAGE_NAME" ]; then
    error "Could not extract package name from pyproject.toml"
fi

info "Package: $PACKAGE_NAME"
info "Source: $PACKAGE_PATH"
echo

# Step 1: Uninstall
info "Step 1/4: Uninstalling $PACKAGE_NAME..."
if uv tool list | grep -q "^$PACKAGE_NAME "; then
    uv tool uninstall "$PACKAGE_NAME" 2>&1 | grep -E "(Uninstalled|executable)" || true
    success "Uninstalled"
else
    warn "Not currently installed (ok)"
fi
echo

# Step 2: Clear UV cache
info "Step 2/4: Clearing UV cache..."
UV_CACHE_DIR="${UV_CACHE_DIR:-$HOME/.cache/uv}"
if [ -d "$UV_CACHE_DIR" ]; then
    BEFORE_SIZE=$(du -sh "$UV_CACHE_DIR" 2>/dev/null | cut -f1 || echo "unknown")
    uv cache clean 2>&1 | head -5 || true
    AFTER_SIZE=$(du -sh "$UV_CACHE_DIR" 2>/dev/null | cut -f1 || echo "unknown")
    success "Cache cleared (was: $BEFORE_SIZE, now: $AFTER_SIZE)"
else
    warn "Cache dir not found: $UV_CACHE_DIR (ok)"
fi
echo

# Step 3: Reinstall
info "Step 3/4: Installing $PACKAGE_NAME from source..."
INSTALL_START=$(date +%s)
uv tool install "$PACKAGE_PATH" 2>&1 | grep -E "(Installed|executable|packages)" || true
INSTALL_END=$(date +%s)
INSTALL_TIME=$((INSTALL_END - INSTALL_START))
success "Installed in ${INSTALL_TIME}s"
echo

# Step 4: Verify
info "Step 4/4: Verifying installation..."

# Find executable
EXECUTABLE=$(uv tool list | grep -A1 "^$PACKAGE_NAME " | grep "^- " | sed 's/^- //' || echo "")
if [ -z "$EXECUTABLE" ]; then
    error "Executable not found after install"
fi

# Check if executable exists
EXECUTABLE_PATH=$(command -v "$EXECUTABLE" 2>/dev/null || echo "")
if [ -z "$EXECUTABLE_PATH" ]; then
    error "Executable not in PATH: $EXECUTABLE"
fi

# Show timestamps
info "Executable: $EXECUTABLE_PATH"
EXEC_TIME=$(stat -f "%Sm" -t "%Y-%m-%d %H:%M:%S" "$EXECUTABLE_PATH" 2>/dev/null || stat -c "%y" "$EXECUTABLE_PATH" 2>/dev/null | cut -d. -f1)
info "Modified: $EXEC_TIME"

# Find site-packages location
SITE_PACKAGES=$(dirname "$EXECUTABLE_PATH")/../lib/python*/site-packages/$PACKAGE_NAME 2>/dev/null
if [ -d "$SITE_PACKAGES" ]; then
    SITE_PACKAGES=$(echo "$SITE_PACKAGES" | head -1)  # Take first match if multiple python versions
    info "Site-packages: $SITE_PACKAGES"

    # Show key file timestamps
    for file in cli.py providers.py __init__.py; do
        if [ -f "$SITE_PACKAGES/$file" ]; then
            FILE_TIME=$(stat -f "%Sm" -t "%H:%M:%S" "$SITE_PACKAGES/$file" 2>/dev/null || stat -c "%y" "$SITE_PACKAGES/$file" 2>/dev/null | cut -d. -f1 | awk '{print $2}')
            info "  $file: $FILE_TIME"
        fi
    done
fi

echo
success "Installation verified!"
echo
info "Test it: echo 'test' | $EXECUTABLE"
