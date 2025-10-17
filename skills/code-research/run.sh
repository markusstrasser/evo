#!/usr/bin/env bash
# Research Workflow Skill - Main orchestration script
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BEST_REPOS="${HOME}/Projects/best"
REPOS_EDN="${SCRIPT_DIR}/data/repos.edn"

# Source .env for API keys
if [[ -f "${SCRIPT_DIR}/../../.env" ]]; then
    source "${SCRIPT_DIR}/../../.env"
fi

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

usage() {
    cat <<EOF
Research Workflow Skill - Query best-of repositories

USAGE:
    run.sh <command> [options]

COMMANDS:
    search <query>              Quick search across project metadata
    explore <project> <query>   Deep dive into specific project
    compare <projects> <aspect> Compare implementations across projects
    list                        List all available projects
    info <project>              Show project details

OPTIONS:
    --model <model>            LLM model to use (google|openai|xai)
    --focused <paths>          Comma-separated paths for focused extraction
    --save-session <name>      Save as named research session
    --help                     Show this help

EXAMPLES:
    # Quick search
    run.sh search "event sourcing patterns"

    # Deep dive
    run.sh explore re-frame "subscription lifecycle"

    # Focused query on large repo
    run.sh explore clojurescript "macro expansion" \\
        --focused "cljs/compiler.clj,cljs/analyzer.cljc"

    # Compare projects
    run.sh compare "re-frame,electric" "reactive state management"

    # List available projects
    run.sh list
EOF
}

check_requirements() {
    local missing=()
    command -v repomix >/dev/null || missing+=("repomix")
    command -v llmx >/dev/null || missing+=("llmx")

    if [[ ${#missing[@]} -gt 0 ]]; then
        echo -e "${RED}✗ Missing required commands: ${missing[*]}${NC}" >&2
        exit 1
    fi

    # Check API keys
    if [[ -z "${GEMINI_API_KEY:-}" ]] && [[ -z "${OPENAI_API_KEY:-}" ]]; then
        echo -e "${RED}✗ No API keys found. Set GEMINI_API_KEY or OPENAI_API_KEY${NC}" >&2
        exit 1
    fi

    echo -e "${GREEN}✓ Environment validated${NC}"
}

list_projects() {
    echo "Available projects in ~/Projects/best/:"
    echo ""

    if [[ -d "$BEST_REPOS" ]]; then
        for dir in "$BEST_REPOS"/*; do
            if [[ -d "$dir" ]]; then
                local name=$(basename "$dir")
                local size=$(du -sh "$dir" 2>/dev/null | cut -f1)
                echo "  - $name ($size)"
            fi
        done
    else
        echo -e "${RED}✗ Best-of repos directory not found: $BEST_REPOS${NC}"
        exit 1
    fi
}

project_info() {
    local project="$1"
    local project_path="${BEST_REPOS}/${project}"

    if [[ ! -d "$project_path" ]]; then
        echo -e "${RED}✗ Project not found: $project${NC}"
        exit 1
    fi

    echo "Project: $project"
    echo "Path: $project_path"
    echo ""
    echo "Size:"
    du -sh "$project_path"
    echo ""
    echo "Structure (top 3 levels):"
    tree -L 3 -d "$project_path" 2>/dev/null || echo "  (tree not available)"
    echo ""
    echo "Languages:"
    tokei "$project_path" 2>/dev/null || echo "  (tokei not available)"
}

search_metadata() {
    local query="$1"
    echo "Searching for: $query"
    echo ""

    # Simple grep through project names and READMEs
    if [[ -d "$BEST_REPOS" ]]; then
        for dir in "$BEST_REPOS"/*; do
            if [[ -d "$dir" ]]; then
                local name=$(basename "$dir")
                if echo "$name" | grep -iq "$query"; then
                    echo -e "${GREEN}✓ $name${NC} (name match)"
                elif [[ -f "$dir/README.md" ]]; then
                    if grep -iq "$query" "$dir/README.md" 2>/dev/null; then
                        echo -e "${YELLOW}~ $name${NC} (README match)"
                    fi
                fi
            fi
        done
    fi
}

explore_project() {
    local project="$1"
    local query="$2"
    local model="${3:-gemini}"
    local focused="${4:-}"

    local project_path="${BEST_REPOS}/${project}"

    if [[ ! -d "$project_path" ]]; then
        echo -e "${RED}✗ Project not found: $project${NC}"
        exit 1
    fi

    echo -e "${GREEN}🔍 Exploring $project...${NC}"

    # Check project size
    local size_mb=$(du -sm "$project_path" | cut -f1)
    echo "Project size: ${size_mb}MB"

    local tmpfile=$(mktemp)

    if [[ -n "$focused" ]]; then
        echo "Extracting focused paths: $focused"
        repomix "$project_path" --include "$focused" --copy --output /dev/null > "$tmpfile" 2>&1
    elif [[ $size_mb -lt 10 ]]; then
        echo "Small project - including full src/"
        repomix "$project_path" --include "src/**,README.md" --copy --output /dev/null > "$tmpfile" 2>&1
    else
        echo "Large project - recommend using --focused option"
        echo "Structure:"
        tree -L 2 -d "$project_path/src" 2>/dev/null || echo "No src/ directory"
        exit 1
    fi

    # Query with selected model
    echo ""
    echo -e "${GREEN}📡 Querying with $model...${NC}"
    echo ""

    case "$model" in
        gemini)
            pbpaste | llmx --provider google "$query"
            ;;
        codex)
            pbpaste | llmx --provider openai --model gpt-5-codex --reasoning-effort high "$query"
            ;;
        grok)
            pbpaste | llmx --provider xai "$query"
            ;;
        *)
            echo -e "${RED}✗ Unknown model: $model${NC}"
            exit 1
            ;;
    esac

    rm -f "$tmpfile"
}

compare_projects() {
    local projects="$1"
    local aspect="$2"
    local model="${3:-codex}"

    IFS=',' read -ra PROJECT_ARRAY <<< "$projects"

    echo -e "${GREEN}🔬 Comparing ${#PROJECT_ARRAY[@]} projects on: $aspect${NC}"
    echo ""

    local combined=$(mktemp)

    for project in "${PROJECT_ARRAY[@]}"; do
        local project_path="${BEST_REPOS}/${project}"

        if [[ ! -d "$project_path" ]]; then
            echo -e "${YELLOW}⚠ Skipping missing project: $project${NC}"
            continue
        fi

        echo "Extracting from $project..."
        echo "=== $project ===" >> "$combined"
        repomix "$project_path" --include "src/**/*.clj*" --copy --output /dev/null 2>/dev/null | head -1000 >> "$combined"
        echo "" >> "$combined"
    done

    echo ""
    echo -e "${GREEN}📡 Comparing with $model...${NC}"
    echo ""

    cat "$combined" | llmx --provider openai --model gpt-5-codex --reasoning-effort high \\
        "Compare how these projects handle: $aspect. Focus on differences in approach, tradeoffs, and best practices."

    rm -f "$combined"
}

# Main command dispatcher
main() {
    if [[ $# -eq 0 ]]; then
        usage
        exit 1
    fi

    local command="$1"
    shift

    case "$command" in
        search)
            [[ $# -eq 0 ]] && { echo "Error: search requires query"; usage; exit 1; }
            check_requirements
            search_metadata "$@"
            ;;
        explore)
            [[ $# -lt 2 ]] && { echo "Error: explore requires project and query"; usage; exit 1; }
            check_requirements
            local project="$1"
            local query="$2"
            shift 2
            local model="gemini"
            local focused=""

            while [[ $# -gt 0 ]]; do
                case "$1" in
                    --model) model="$2"; shift 2 ;;
                    --focused) focused="$2"; shift 2 ;;
                    *) echo "Unknown option: $1"; usage; exit 1 ;;
                esac
            done

            explore_project "$project" "$query" "$model" "$focused"
            ;;
        compare)
            [[ $# -lt 2 ]] && { echo "Error: compare requires projects and aspect"; usage; exit 1; }
            check_requirements
            compare_projects "$1" "$2" "${3:-codex}"
            ;;
        list)
            list_projects
            ;;
        info)
            [[ $# -eq 0 ]] && { echo "Error: info requires project name"; usage; exit 1; }
            project_info "$1"
            ;;
        --help|-h|help)
            usage
            ;;
        *)
            echo "Unknown command: $command"
            usage
            exit 1
            ;;
    esac
}

main "$@"
