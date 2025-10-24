#!/usr/bin/env bash
# Session Memory & Analysis Skill - Read and analyze Claude Code sessions
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="${SCRIPT_DIR}/lib"
CORE_BB="${LIB_DIR}/core.bb"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

usage() {
    cat <<EOF
Session Memory & Analysis - Read and analyze Claude Code conversations

USAGE:
    run.sh <command> [options]

COMMANDS:
    list [--all] [--limit N]                 List recent sessions
    read <session-id> [--limit N] [--role user|assistant]
    analyze <session-id> [--type tools|skills|errors]
    search <query> [--context N]             Search across sessions
    skills <session-id> [--generate]         Extract potential skills
    export <session-id> [--format md|json]   Export session
    info <session-id>                        Show session metadata

OPTIONS:
    --all               Include all projects (not just current)
    --limit N           Limit results to N items
    --role <role>       Filter by role (user|assistant)
    --type <type>       Analysis type (tools|skills|errors)
    --context N         Show N lines of context
    --generate          Generate skill template
    --format <fmt>      Export format (markdown|json)
    --help              Show this help

EXAMPLES:
    # List recent sessions
    run.sh list

    # Read specific session
    run.sh read 0c7b3880-e100-49c2-983b-1aa4ff2bb82e --limit 10

    # Analyze tool usage
    run.sh analyze 0c7b3880-e100-49c2-983b-1aa4ff2bb82e --type tools

    # Search for kernel discussions
    run.sh search "kernel IR" "compounding"

    # Export to markdown
    run.sh export 0c7b3880-e100-49c2-983b-1aa4ff2bb82e --format markdown

EOF
}

# Parse arguments
cmd="${1:-}"
shift || true

case "$cmd" in
    list)
        bb -e "(load-file \"${CORE_BB}\") \
                (doseq [session (list-sessions {:all? $([[ \"$1\" == \"--all\" ]] && echo true || echo false)})] \
                  (println (format \"%s | %s msgs | %s\" (:id session) (:size session) (:modified session))))"
        ;;

    read)
        session_id="${1:-}"
        if [[ -z "$session_id" ]]; then
            echo -e "${RED}Error: session-id required${NC}"
            usage
            exit 1
        fi
        shift || true

        limit_arg=""
        role_arg=""
        while [[ $# -gt 0 ]]; do
            case "$1" in
                --limit) limit_arg=":limit $2"; shift 2 ;;
                --role) role_arg=":role :$2"; shift 2 ;;
                *) shift ;;
            esac
        done

        bb -e "(load-file \"${CORE_BB}\") \
                (doseq [msg (read-session \"$session_id\" $limit_arg $role_arg)] \
                  (println (format \"[%s] %s\" (:role msg) (subs (:content msg) 0 (min 200 (count (:content msg)))))))"
        ;;

    analyze)
        session_id="${1:-}"
        if [[ -z "$session_id" ]]; then
            echo -e "${RED}Error: session-id required${NC}"
            usage
            exit 1
        fi
        shift || true

        analysis_type="${2:-tools}"

        case "$analysis_type" in
            tools)
                echo -e "${BLUE}Tool Usage Analysis${NC}"
                bb -e "(load-file \"${CORE_BB}\") (require '[clojure.pprint :as pp]) \
                       (pp/print-table [:tool :count :success :failed :success-rate] \
                         (tool-usage-stats \"$session_id\"))"
                ;;
            *)
                echo -e "${YELLOW}Analysis type '$analysis_type' not yet implemented${NC}"
                ;;
        esac
        ;;

    search)
        if [[ $# -eq 0 ]]; then
            echo -e "${RED}Error: search query required${NC}"
            usage
            exit 1
        fi

        # Parse search mode and query
        mode="hybrid"  # default
        query=""
        limit=10
        threshold=0.6

        while [[ $# -gt 0 ]]; do
            case "$1" in
                --sem) mode="sem"; shift ;;
                --lex) mode="lex"; shift ;;
                --hybrid) mode="hybrid"; shift ;;
                --limit) limit="$2"; shift 2 ;;
                --threshold) threshold="$2"; shift 2 ;;
                --context) context="$2"; shift 2 ;;
                *) query="$query $1"; shift ;;
            esac
        done

        query=$(echo "$query" | xargs)  # trim whitespace

        sessions_dir="${HOME}/.claude/projects/-Users-alien-Projects-evo"

        echo -e "${BLUE}Searching sessions: ${query}${NC}"
        echo -e "${YELLOW}Mode: $mode | Limit: $limit | Threshold: $threshold${NC}\n"

        case "$mode" in
            sem)
                ck --sem --limit "$limit" --threshold "$threshold" --scores "$query" "$sessions_dir"
                ;;
            lex)
                ck --lex --limit "$limit" "$query" "$sessions_dir"
                ;;
            hybrid)
                ck --hybrid --limit "$limit" --threshold "$threshold" --scores "$query" "$sessions_dir"
                ;;
            *)
                # Fallback to grep
                rg --heading --line-number --context 2 "$query" "$sessions_dir"
                ;;
        esac
        ;;

    skills)
        session_id="${1:-}"
        if [[ -z "$session_id" ]]; then
            echo -e "${RED}Error: session-id required${NC}"
            usage
            exit 1
        fi

        echo -e "${YELLOW}Skill extraction not yet implemented${NC}"
        ;;

    export)
        session_id="${1:-}"
        if [[ -z "$session_id" ]]; then
            echo -e "${RED}Error: session-id required${NC}"
            usage
            exit 1
        fi
        shift || true

        format="markdown"
        if [[ "${1:-}" == "--format" ]]; then
            format="${2:-markdown}"
        fi

        echo -e "${YELLOW}Export to $format not yet implemented${NC}"
        ;;

    info)
        session_id="${1:-}"
        if [[ -z "$session_id" ]]; then
            echo -e "${RED}Error: session-id required${NC}"
            usage
            exit 1
        fi

        bb -e "(load-file \"${CORE_BB}\") (require '[clojure.pprint :as pp]) \
                (pp/pprint (session-info \"$session_id\"))"
        ;;

    --help|-h|help)
        usage
        ;;

    "")
        usage
        ;;

    *)
        echo -e "${RED}Unknown command: $cmd${NC}"
        usage
        exit 1
        ;;
esac
