#!/usr/bin/env bash
# Dev Diagnostics Skill - Environment validation and health checks
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/../.."
DEV_DIR="${PROJECT_ROOT}/dev"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

usage() {
    cat <<EOF
Dev Diagnostics Skill - Environment validation and health checks

USAGE:
    run.sh <command> [options]

COMMANDS:
    health                 Quick health check (Java, Clojure, Node, etc.)
    preflight              Thorough pre-flight checks
    cache <action>         Cache management (status|clear [type])
    diagnose <error>       Diagnose error using catalog
    api-keys <action>      API key validation (check|validate|required)
    deps <action>          Dependency checks (outdated|verify|tree)
    env-debug <var>        Debug .env variable hierarchy
    uv-reinstall           Reinstall UV Python environment
    help                   Show this help

EXAMPLES:
    # Quick health check
    run.sh health

    # Pre-flight before starting work
    run.sh preflight

    # Clear all caches
    run.sh cache clear

    # Clear specific cache
    run.sh cache clear shadow

    # Diagnose error
    run.sh diagnose "Cannot resolve symbol"

    # Check API keys
    run.sh api-keys check

    # Debug .env variable
    run.sh env-debug GEMINI_API_KEY

    # Reinstall UV environment
    run.sh uv-reinstall
EOF
}

health_check() {
    echo -e "${BLUE}=== Environment Health Check ===${NC}"
    echo ""

    local failed=0

    # Check Java
    if command -v java >/dev/null 2>&1; then
        local java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
        echo -e "${GREEN}✓${NC} Java $java_version"
    else
        echo -e "${RED}✗${NC} Java not found"
        ((failed++))
    fi

    # Check Clojure
    if command -v clojure >/dev/null 2>&1; then
        local clj_version=$(clojure --version 2>&1 | grep "Clojure CLI" | awk '{print $NF}')
        echo -e "${GREEN}✓${NC} Clojure CLI $clj_version"
    else
        echo -e "${RED}✗${NC} Clojure not found"
        ((failed++))
    fi

    # Check Node
    if command -v node >/dev/null 2>&1; then
        local node_version=$(node --version)
        echo -e "${GREEN}✓${NC} Node.js $node_version"
    else
        echo -e "${RED}✗${NC} Node.js not found"
        ((failed++))
    fi

    # Check npm
    if command -v npm >/dev/null 2>&1; then
        local npm_version=$(npm --version)
        echo -e "${GREEN}✓${NC} npm $npm_version"
    else
        echo -e "${RED}✗${NC} npm not found"
        ((failed++))
    fi

    # Check Shadow-CLJS
    if npm list shadow-cljs >/dev/null 2>&1; then
        local shadow_version=$(npm list shadow-cljs 2>/dev/null | grep shadow-cljs | awk -F@ '{print $NF}')
        echo -e "${GREEN}✓${NC} Shadow-CLJS $shadow_version"
    else
        echo -e "${YELLOW}⚠${NC} Shadow-CLJS not in node_modules (run npm install)"
    fi

    # Check Git
    if command -v git >/dev/null 2>&1; then
        if git rev-parse --git-dir >/dev/null 2>&1; then
            local git_status=$(git status --porcelain | wc -l)
            if [[ $git_status -eq 0 ]]; then
                echo -e "${GREEN}✓${NC} Git repository (clean)"
            else
                echo -e "${YELLOW}⚠${NC} Git repository ($git_status uncommitted changes)"
            fi
        else
            echo -e "${YELLOW}⚠${NC} Not a git repository"
        fi
    fi

    # Check API keys if .env exists
    if [[ -f "${PROJECT_ROOT}/.env" ]]; then
        source "${PROJECT_ROOT}/.env" 2>/dev/null || true
        local keys_present=0
        local keys_total=0

        for key in GEMINI_API_KEY OPENAI_API_KEY GROK_API_KEY; do
            ((keys_total++))
            if [[ -n "${!key:-}" ]]; then
                ((keys_present++))
            fi
        done

        if [[ $keys_present -eq $keys_total ]]; then
            echo -e "${GREEN}✓${NC} API keys present (all $keys_total)"
        elif [[ $keys_present -gt 0 ]]; then
            echo -e "${YELLOW}⚠${NC} API keys partial ($keys_present/$keys_total present)"
        else
            echo -e "${YELLOW}⚠${NC} No API keys found in .env"
        fi
    else
        echo -e "${YELLOW}⚠${NC} No .env file (API keys missing)"
    fi

    echo ""
    if [[ $failed -eq 0 ]]; then
        echo -e "${GREEN}All required checks passed!${NC}"
        return 0
    else
        echo -e "${RED}$failed checks failed${NC}"
        return 1
    fi
}

preflight_check() {
    echo -e "${BLUE}=== Pre-Flight Checks ===${NC}"
    echo ""

    # Run health check first
    if ! health_check; then
        echo ""
        echo -e "${RED}Health check failed - fix issues before continuing${NC}"
        return 1
    fi

    echo ""
    echo -e "${BLUE}Additional checks:${NC}"

    # Check cache sizes
    echo -n "Checking cache sizes... "
    if [[ -d ".shadow-cljs" ]]; then
        local shadow_size=$(du -sh .shadow-cljs 2>/dev/null | cut -f1)
        echo -e "${GREEN}Shadow-CLJS cache: $shadow_size${NC}"
    fi

    # Check for running processes
    if pgrep -f "shadow-cljs" >/dev/null 2>&1; then
        echo -e "${GREEN}✓${NC} Shadow-CLJS server running"
    else
        echo -e "${YELLOW}⚠${NC} Shadow-CLJS server not running"
    fi

    echo ""
    echo -e "${GREEN}Pre-flight checks complete!${NC}"
}

cache_status() {
    echo -e "${BLUE}=== Cache Status ===${NC}"
    echo ""

    for cache_dir in ".shadow-cljs" ".clj-kondo/.cache" ".cpcache" "node_modules/.cache" ".cache"; do
        if [[ -d "$cache_dir" ]]; then
            local size=$(du -sh "$cache_dir" 2>/dev/null | cut -f1)
            echo "$cache_dir: $size"
        else
            echo "$cache_dir: not present"
        fi
    done
}

cache_clear() {
    local type="${1:-all}"

    case "$type" in
        shadow|shadow-cljs)
            echo "Clearing Shadow-CLJS cache..."
            rm -rf .shadow-cljs/
            echo -e "${GREEN}✓ Shadow-CLJS cache cleared${NC}"
            ;;
        clj-kondo)
            echo "Clearing Clj-kondo cache..."
            rm -rf .clj-kondo/.cache/
            echo -e "${GREEN}✓ Clj-kondo cache cleared${NC}"
            ;;
        clojure|clj)
            echo "Clearing Clojure cache..."
            rm -rf .cpcache/
            echo -e "${GREEN}✓ Clojure cache cleared${NC}"
            ;;
        npm)
            echo "Clearing npm cache..."
            rm -rf node_modules/.cache/
            echo -e "${GREEN}✓ npm cache cleared${NC}"
            ;;
        skills)
            echo "Clearing skills cache..."
            rm -rf .cache/
            echo -e "${GREEN}✓ Skills cache cleared${NC}"
            ;;
        all)
            echo "Clearing all caches..."
            rm -rf .shadow-cljs/ .clj-kondo/.cache/ .cpcache/ node_modules/.cache/ .cache/
            echo -e "${GREEN}✓ All caches cleared${NC}"
            ;;
        *)
            echo -e "${RED}Unknown cache type: $type${NC}"
            echo "Options: shadow, clj-kondo, clojure, npm, skills, all"
            return 1
            ;;
    esac
}

diagnose_error() {
    local error_msg="$1"

    echo -e "${BLUE}Diagnosing error...${NC}"
    echo "Error: $error_msg"
    echo ""

    # Common patterns
    if echo "$error_msg" | grep -qi "cannot resolve symbol"; then
        echo -e "${YELLOW}Likely cause:${NC} Missing require or typo"
        echo -e "${GREEN}Fix:${NC} Add (require '[namespace :as alias])"
        echo "       or check spelling"

    elif echo "$error_msg" | grep -qi "unexpected error\|strange"; then
        echo -e "${YELLOW}Likely cause:${NC} Stale cache"
        echo -e "${GREEN}Fix:${NC} ./run.sh cache clear"

    elif echo "$error_msg" | grep -qi "port.*in use\|address.*in use"; then
        echo -e "${YELLOW}Likely cause:${NC} Server already running"
        echo -e "${GREEN}Fix:${NC} pkill -f shadow-cljs"

    elif echo "$error_msg" | grep -qi "api.*key\|authentication"; then
        echo -e "${YELLOW}Likely cause:${NC} Missing or invalid API key"
        echo -e "${GREEN}Fix:${NC} Check .env file and source it"

    else
        echo -e "${YELLOW}No specific diagnosis found${NC}"
        echo "Common fixes:"
        echo "  1. Clear cache: ./run.sh cache clear"
        echo "  2. Check environment: ./run.sh health"
        echo "  3. Restart REPL/server"
    fi
}

api_keys_check() {
    local action="${1:-check}"

    case "$action" in
        check)
            echo -e "${BLUE}=== API Keys Check ===${NC}"
            if [[ -f .env ]]; then
                source .env 2>/dev/null || true
                echo "Required keys:"
                for key in GEMINI_API_KEY OPENAI_API_KEY GROK_API_KEY; do
                    if [[ -n "${!key:-}" ]]; then
                        echo -e "  ${GREEN}✓${NC} $key"
                    else
                        echo -e "  ${RED}✗${NC} $key"
                    fi
                done
            else
                echo -e "${RED}.env file not found${NC}"
                return 1
            fi
            ;;
        required)
            echo "Required API keys:"
            echo "  - GEMINI_API_KEY"
            echo "  - OPENAI_API_KEY"
            echo "  - GROK_API_KEY"
            echo ""
            echo "Optional:"
            echo "  - ANTHROPIC_API_KEY"
            echo "  - GROQ_API_KEY"
            ;;
        validate)
            echo "API key validation would make actual API calls"
            echo "Use 'check' to verify keys are present"
            ;;
        *)
            echo "Unknown action: $action"
            echo "Options: check, required, validate"
            return 1
            ;;
    esac
}

deps_check() {
    local action="${1:-outdated}"

    case "$action" in
        outdated)
            echo "Checking for outdated dependencies..."
            if command -v clojure >/dev/null 2>&1; then
                clojure -M:outdated 2>/dev/null || echo "Run: clojure -Sdeps '{:deps {com.github.liquidz/antq {:mvn/version \"RELEASE\"}}}' -M -m antq.core"
            fi
            ;;
        verify)
            echo "Verifying dependencies..."
            clojure -Spath >/dev/null && echo -e "${GREEN}✓ Dependencies OK${NC}"
            ;;
        tree)
            echo "Dependency tree:"
            clojure -Stree 2>/dev/null | head -50
            ;;
        *)
            echo "Unknown action: $action"
            echo "Options: outdated, verify, tree"
            return 1
            ;;
    esac
}

main() {
    if [[ $# -eq 0 ]]; then
        usage
        exit 1
    fi

    case "$1" in
        health)
            health_check
            ;;
        preflight)
            preflight_check
            ;;
        cache)
            shift
            local action="${1:-status}"
            case "$action" in
                status) cache_status ;;
                clear) shift; cache_clear "${1:-all}" ;;
                *) echo "Unknown cache action: $action"; exit 1 ;;
            esac
            ;;
        diagnose)
            shift
            [[ $# -eq 0 ]] && { echo "Error: diagnose requires error message"; exit 1; }
            diagnose_error "$*"
            ;;
        api-keys)
            shift
            api_keys_check "${1:-check}"
            ;;
        deps)
            shift
            deps_check "${1:-outdated}"
            ;;
        env-debug)
            shift
            "${SCRIPT_DIR}/lib/env_debug.bb" "$@"
            ;;
        uv-reinstall)
            "${SCRIPT_DIR}/bin/uv-dev-reinstall.sh"
            ;;
        help|--help|-h)
            usage
            ;;
        *)
            echo "Unknown command: $1"
            usage
            exit 1
            ;;
    esac
}

main "$@"
