#!/usr/bin/env bash
# Debug .env file hierarchy and show which values are actually loaded
#
# Usage:
#   scripts/debug-env.sh                    # Show all .env files and their vars
#   scripts/debug-env.sh GEMINI_API_KEY     # Trace specific variable
#   scripts/debug-env.sh --conflicts        # Show only conflicting vars
#
# Problem this solves:
#   Multiple .env files in parent directories can override each other.
#   Hard to see which value actually gets loaded.
#
# What it does:
#   1. Scans current directory up to home for .env files
#   2. Shows what vars each file defines
#   3. Shows actual loaded env var values
#   4. Highlights conflicts/overrides
#   5. Traces specific var through hierarchy if requested

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
GRAY='\033[0;90m'
NC='\033[0m'

# Parse args
SPECIFIC_VAR=""
CONFLICTS_ONLY=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --conflicts)
            CONFLICTS_ONLY=true
            shift
            ;;
        --help|-h)
            echo "Usage: $0 [VAR_NAME] [--conflicts]"
            echo
            echo "Examples:"
            echo "  $0                      # Show all .env files and vars"
            echo "  $0 GEMINI_API_KEY       # Trace GEMINI_API_KEY through hierarchy"
            echo "  $0 --conflicts          # Show only vars with conflicts"
            exit 0
            ;;
        *)
            SPECIFIC_VAR="$1"
            shift
            ;;
    esac
done

# Find all .env files from current dir up to home
find_env_files() {
    local current="$PWD"
    local files=()

    while [ "$current" != "/" ] && [ "$current" != "$HOME/.." ]; do
        if [ -f "$current/.env" ]; then
            files+=("$current/.env")
        fi
        current=$(dirname "$current")
    done

    # Reverse array so parent dirs come first
    local reversed=()
    for ((i=${#files[@]}-1; i>=0; i--)); do
        reversed+=("${files[$i]}")
    done

    printf '%s\n' "${reversed[@]}"
}

# Extract vars from .env file (handles export, comments, quotes)
extract_vars() {
    local file="$1"
    grep -E '^[A-Z_][A-Z0-9_]*=' "$file" 2>/dev/null | \
        sed -E 's/^export //' | \
        cut -d= -f1 | \
        sort -u
}

# Get value from .env file
get_file_value() {
    local file="$1"
    local var="$2"
    grep -E "^(export )?${var}=" "$file" 2>/dev/null | \
        head -1 | \
        sed -E 's/^export //' | \
        cut -d= -f2- | \
        sed -E 's/^["'"'"']//; s/["'"'"']$//' || echo ""
}

# Main
echo
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}   .env File Hierarchy Debugger${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo

# Find all .env files
mapfile -t ENV_FILES < <(find_env_files)

if [ ${#ENV_FILES[@]} -eq 0 ]; then
    echo -e "${YELLOW}⚠ No .env files found in current directory or parents${NC}"
    echo
    exit 0
fi

echo -e "${CYAN}Found ${#ENV_FILES[@]} .env file(s):${NC}"
for file in "${ENV_FILES[@]}"; do
    echo -e "  ${GRAY}$(dirname "$file")/${NC}.env"
done
echo

# If specific var requested, show just that
if [ -n "$SPECIFIC_VAR" ]; then
    echo -e "${CYAN}Tracing: ${YELLOW}$SPECIFIC_VAR${NC}"
    echo

    FOUND=false
    for file in "${ENV_FILES[@]}"; do
        if grep -qE "^(export )?${SPECIFIC_VAR}=" "$file" 2>/dev/null; then
            VALUE=$(get_file_value "$file" "$SPECIFIC_VAR")
            VALUE_PREVIEW="${VALUE:0:20}$([ ${#VALUE} -gt 20 ] && echo "...")"

            DIR=$(dirname "$file")
            echo -e "${BLUE}📄 $DIR/${NC}.env"
            echo -e "   ${SPECIFIC_VAR}=${GREEN}$VALUE_PREVIEW${NC}"
            echo

            FOUND=true
        fi
    done

    if [ "$FOUND" = false ]; then
        echo -e "${YELLOW}⚠ Variable not found in any .env file${NC}"
        echo
    fi

    # Show actual loaded value
    if [ -n "${!SPECIFIC_VAR:-}" ]; then
        ACTUAL="${!SPECIFIC_VAR}"
        ACTUAL_PREVIEW="${ACTUAL:0:20}$([ ${#ACTUAL} -gt 20 ] && echo "...")"
        echo -e "${GREEN}✓ Currently loaded value:${NC}"
        echo -e "   ${SPECIFIC_VAR}=${CYAN}$ACTUAL_PREVIEW${NC}"
        echo

        # Check if it matches any file
        MATCHES=false
        for file in "${ENV_FILES[@]}"; do
            FILE_VALUE=$(get_file_value "$file" "$SPECIFIC_VAR")
            if [ "$ACTUAL" = "$FILE_VALUE" ]; then
                echo -e "${GREEN}✓ Matches: $(dirname "$file")/.env${NC}"
                MATCHES=true
                break
            fi
        done

        if [ "$MATCHES" = false ]; then
            echo -e "${RED}✗ Does not match any .env file!${NC}"
            echo -e "${YELLOW}  Value may be from shell environment or other source${NC}"
        fi
    else
        echo -e "${RED}✗ Variable not set in current environment${NC}"
    fi
    echo

    exit 0
fi

# Show all vars with conflicts
declare -A VAR_FILES    # var -> list of files that define it
declare -A VAR_VALUES   # var@file -> value

# Collect all vars from all files
for file in "${ENV_FILES[@]}"; do
    while IFS= read -r var; do
        VAR_FILES["$var"]+="$file|"
        VALUE=$(get_file_value "$file" "$var")
        VAR_VALUES["$var@$file"]="$VALUE"
    done < <(extract_vars "$file")
done

# Find conflicts
CONFLICTS=()
for var in "${!VAR_FILES[@]}"; do
    IFS='|' read -ra FILES <<< "${VAR_FILES[$var]}"
    if [ ${#FILES[@]} -gt 1 ]; then
        CONFLICTS+=("$var")
    fi
done

# Show conflicts first
if [ ${#CONFLICTS[@]} -gt 0 ]; then
    echo -e "${RED}⚠ Variables with conflicts: ${#CONFLICTS[@]}${NC}"
    echo

    for var in "${CONFLICTS[@]}"; do
        echo -e "${YELLOW}  $var${NC}"

        IFS='|' read -ra FILES <<< "${VAR_FILES[$var]}"
        for file in "${FILES[@]}"; do
            [ -z "$file" ] && continue
            VALUE="${VAR_VALUES["$var@$file"]}"
            VALUE_PREVIEW="${VALUE:0:30}$([ ${#VALUE} -gt 30 ] && echo "...")"

            DIR=$(dirname "$file")
            echo -e "    ${GRAY}$DIR/${NC}.env: ${CYAN}$VALUE_PREVIEW${NC}"
        done

        # Show actual loaded value
        if [ -n "${!var:-}" ]; then
            ACTUAL="${!var}"
            ACTUAL_PREVIEW="${ACTUAL:0:30}$([ ${#ACTUAL} -gt 30 ] && echo "...")"
            echo -e "    ${GREEN}→ Loaded:${NC} ${CYAN}$ACTUAL_PREVIEW${NC}"
        else
            echo -e "    ${RED}→ Not loaded${NC}"
        fi
        echo
    done
fi

# If --conflicts flag, stop here
if [ "$CONFLICTS_ONLY" = true ]; then
    exit 0
fi

# Show all vars (grouped by file)
if [ ${#CONFLICTS[@]} -gt 0 ]; then
    echo -e "${GRAY}───────────────────────────────────────────────────────────${NC}"
    echo
fi

echo -e "${CYAN}All variables by file:${NC}"
echo

for file in "${ENV_FILES[@]}"; do
    DIR=$(dirname "$file")
    echo -e "${BLUE}📄 $DIR/${NC}.env"

    mapfile -t VARS < <(extract_vars "$file" | sort)

    if [ ${#VARS[@]} -eq 0 ]; then
        echo -e "   ${GRAY}(no variables)${NC}"
    else
        for var in "${VARS[@]}"; do
            VALUE=$(get_file_value "$file" "$var")
            VALUE_PREVIEW="${VALUE:0:40}$([ ${#VALUE} -gt 40 ] && echo "...")"

            # Mark if conflicted
            IS_CONFLICT=""
            for conflict in "${CONFLICTS[@]}"; do
                if [ "$var" = "$conflict" ]; then
                    IS_CONFLICT="${YELLOW}⚠${NC} "
                    break
                fi
            done

            echo -e "   $IS_CONFLICT${var}=${GRAY}$VALUE_PREVIEW${NC}"
        done
    fi
    echo
done

echo -e "${GRAY}───────────────────────────────────────────────────────────${NC}"
echo
echo -e "${CYAN}Tips:${NC}"
echo -e "  • Run: ${GRAY}$0 VAR_NAME${NC} to trace a specific variable"
echo -e "  • Run: ${GRAY}$0 --conflicts${NC} to see only conflicts"
echo -e "  • ${YELLOW}⚠${NC} means variable defined in multiple files"
echo
