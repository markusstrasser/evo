#!/usr/bin/env bash
# Architect Skill - Main orchestration script
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="${SCRIPT_DIR}/lib"

# NOTE: llmx auto-loads .env files, no need to source manually

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Detect Python command (prefer uv if available)
get_python_cmd() {
    if command -v uv >/dev/null 2>&1; then
        echo "uv run python"
    elif command -v python3 >/dev/null 2>&1; then
        echo "python3"
    else
        echo "python"
    fi
}

PYTHON_CMD=$(get_python_cmd)

usage() {
    cat <<EOF
Architect Skill - Architectural decision-making workflow

USAGE:
    run.sh <command> [options]

COMMANDS:
    review <description>            Full cycle (generate → rank → present)
    propose <description>           Generate proposals from LLM providers
    rank <run-id>                   Rank proposals via tournament
    refine <run-id> <proposal-id> <feedback>  Refine a proposal
    decide <run-id> <decision> [proposal-id] [reason]  Record decision as ADR

    list                            List all review runs
    show <run-id>                   Show run details
    ledger                          Show provenance ledger

OPTIONS (for review/rank/propose):
    --constraints-file <path>       Path to project constraints file (default: .architect/project-constraints.md)

OPTIONS (for review/rank):
    --auto-decide                   Auto-approve if confidence > threshold
    --confidence <float>            Confidence threshold (default: 0.85/0.80)

OPTIONS (for propose):
    --providers <list>              Comma-separated providers (default: gemini,codex,grok)
                                    Available: gemini (Gemini 2.5 Pro), codex (GPT-5 Pro), grok (Grok 4)

OPTIONS (for refine):
    --max-rounds <int>              Max refinement rounds (default: 5)

EXAMPLES:
    # Quick review
    run.sh review "How should we implement undo/redo?"

    # Step-by-step
    run.sh propose "State management approach"
    run.sh rank <run-id>
    run.sh decide <run-id> approve <proposal-id> "Best approach"

    # Auto-decide
    run.sh review "Error handling strategy" --auto-decide --confidence 0.85

EOF
}

check_requirements() {
    local missing=()
    # Check for uv or python3
    if ! command -v uv >/dev/null 2>&1 && ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
        missing+=("python3 or uv")
    fi

    # Check API keys
    if [[ -z "${GEMINI_API_KEY:-}" ]] && [[ -z "${OPENAI_API_KEY:-}" ]] && [[ -z "${XAI_API_KEY:-}" ]]; then
        echo -e "${RED}✗ No API keys found. Set at least one of: GEMINI_API_KEY, OPENAI_API_KEY, XAI_API_KEY${NC}" >&2
        exit 1
    fi

    if [[ ${#missing[@]} -gt 0 ]]; then
        echo -e "${RED}✗ Missing required commands: ${missing[*]}${NC}" >&2
        exit 1
    fi

    echo -e "${GREEN}✓ Environment validated${NC}"
}

cmd_review() {
    local description="$1"
    shift

    local auto_decide=false
    local confidence=0.85
    local constraints_file=""

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --auto-decide) auto_decide=true; shift ;;
            --confidence) confidence="$2"; shift 2 ;;
            --constraints-file) constraints_file="$2"; shift 2 ;;
            *) echo "Unknown option: $1"; usage; exit 1 ;;
        esac
    done

    echo -e "${BLUE}🚀 Starting review cycle${NC}"

    # Convert bash boolean to Python boolean
    local py_auto_decide=$([[ "$auto_decide" == "true" ]] && echo "True" || echo "False")

    # Build constraints_file argument
    local py_constraints_arg=""
    if [[ -n "$constraints_file" ]]; then
        py_constraints_arg=", constraints_file=Path('$constraints_file')"
    fi

    $PYTHON_CMD -c "
import sys
sys.path.insert(0, '$LIB_DIR')
import architect
import json
from pathlib import Path

result = architect.review_cycle(
    description='$description',
    auto_decide=$py_auto_decide,
    confidence_threshold=$confidence$py_constraints_arg,
    verbose=True
)

print()
print('=' * 60)
print('RESULTS')
print('=' * 60)
print(json.dumps(result, indent=2))
"
}

cmd_propose() {
    local description="$1"
    shift

    local providers="gemini,codex,grok"
    local constraints_file=""

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --providers) providers="$2"; shift 2 ;;
            --constraints-file) constraints_file="$2"; shift 2 ;;
            *) echo "Unknown option: $1"; usage; exit 1 ;;
        esac
    done

    echo -e "${BLUE}📋 Generating proposals${NC}"

    # Build constraints_file argument
    local py_constraints_arg=""
    if [[ -n "$constraints_file" ]]; then
        py_constraints_arg=", constraints_file=Path('$constraints_file')"
    fi

    $PYTHON_CMD -c "
import sys
sys.path.insert(0, '$LIB_DIR')
import architect
import json
from pathlib import Path

provider_list = [p.strip() for p in '$providers'.split(',')]

result = architect.propose(
    description='$description',
    provider_names=provider_list$py_constraints_arg,
    verbose=True
)

print()
print('=' * 60)
print('RESULTS')
print('=' * 60)
print(f'Run ID: {result[\"run_id\"]}')
print(f'Proposals: {len(result[\"proposals\"])}')
print()
print('Next steps:')
print(f'  run.sh rank {result[\"run_id\"]}')
"
}

cmd_rank() {
    local run_id="$1"
    shift

    local auto_decide=false
    local confidence=0.8
    local constraints_file=""

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --auto-decide) auto_decide=true; shift ;;
            --confidence) confidence="$2"; shift 2 ;;
            --constraints-file) constraints_file="$2"; shift 2 ;;
            *) echo "Unknown option: $1"; usage; exit 1 ;;
        esac
    done

    echo -e "${BLUE}⚖️  Ranking proposals${NC}"

    # Convert bash boolean to Python boolean
    local py_auto_decide=$([[ "$auto_decide" == "true" ]] && echo "True" || echo "False")

    # Build constraints_file argument
    local py_constraints_arg=""
    if [[ -n "$constraints_file" ]]; then
        py_constraints_arg=", constraints_file=Path('$constraints_file')"
    fi

    $PYTHON_CMD -c "
import sys
sys.path.insert(0, '$LIB_DIR')
import architect
import json
from pathlib import Path

result = architect.rank_proposals(
    run_id='$run_id',
    auto_decide=$py_auto_decide,
    confidence_threshold=$confidence$py_constraints_arg,
    verbose=True
)

print()
print('=' * 60)
print('RESULTS')
print('=' * 60)
print(f'Winner: {result[\"winner_id\"]}')
print(f'Confidence: {result[\"confidence\"]:.1%}')
print(f'Valid: {result[\"valid\"]}')
print(f'Auto-decided: {result[\"auto_decided\"]}')
print()
if not result['auto_decided']:
    print('Next actions:')
    print(f'  Approve: run.sh decide $run_id approve {result[\"winner_id\"]} \"reason\"')
    print(f'  Refine:  run.sh refine $run_id {result[\"winner_id\"]} \"feedback\"')
    print(f'  Reject:  run.sh propose \"revised description\"')
"
}

cmd_refine() {
    local run_id="$1"
    local proposal_id="$2"
    local feedback="$3"
    shift 3

    local max_rounds=5

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --max-rounds) max_rounds="$2"; shift 2 ;;
            *) echo "Unknown option: $1"; usage; exit 1 ;;
        esac
    done

    echo -e "${BLUE}🔄 Refining proposal${NC}"

    $PYTHON_CMD -c "
import sys
sys.path.insert(0, '$LIB_DIR')
import architect
import json

result = architect.refine(
    run_id='$run_id',
    proposal_id='$proposal_id',
    feedback='$feedback',
    max_rounds=$max_rounds,
    verbose=True
)

print()
print('=' * 60)
print('RESULTS')
print('=' * 60)
print(f'Spec ID: {result[\"spec_id\"]}')
print(f'Rounds: {result[\"rounds\"]}')
print(f'Passed: {result[\"passed\"]}')
print()
print('Next: run.sh decide $run_id approve $proposal_id \"reason\"')
"
}

cmd_decide() {
    local run_id="$1"
    local decision="$2"
    local proposal_id="${3:-}"
    local reason="${4:-}"

    echo -e "${BLUE}📝 Recording decision${NC}"

    $PYTHON_CMD -c "
import sys
sys.path.insert(0, '$LIB_DIR')
import architect
import json

proposal_id = '$proposal_id' if '$proposal_id' else None
reason = '$reason' if '$reason' else ''

result = architect.decide(
    run_id='$run_id',
    decision_type='$decision',
    proposal_id=proposal_id,
    reason=reason,
    verbose=True
)

print()
print('=' * 60)
print('DECISION RECORDED')
print('=' * 60)
print(f'ADR ID: {result[\"adr_id\"]}')
print(f'ADR Path: {result[\"adr_uri\"]}')
"
}

cmd_list() {
    local runs_dir=".architect/review-runs"

    if [[ ! -d "$runs_dir" ]]; then
        echo "No review runs found"
        exit 0
    fi

    echo "Review Runs:"
    echo ""

    for run_dir in "$runs_dir"/*; do
        if [[ -d "$run_dir" ]]; then
            local run_id=$(basename "$run_dir")
            local run_file="$run_dir/run.json"

            if [[ -f "$run_file" ]]; then
                local status=$($PYTHON_CMD -c "import json; print(json.load(open('$run_file'))['status'])")
                local desc=$($PYTHON_CMD -c "import json; d=json.load(open('$run_file'))['description']; print(d[:60] + '...' if len(d) > 60 else d)")
                echo "  $run_id"
                echo "    Status: $status"
                echo "    Description: $desc"
                echo ""
            fi
        fi
    done
}

cmd_show() {
    local run_id="$1"
    local run_file=".architect/review-runs/$run_id/run.json"

    if [[ ! -f "$run_file" ]]; then
        echo -e "${RED}✗ Run not found: $run_id${NC}"
        exit 1
    fi

    $PYTHON_CMD -c "
import json
with open('$run_file') as f:
    data = json.load(f)
    print(json.dumps(data, indent=2))
"
}

cmd_ledger() {
    local ledger_file=".architect/review-ledger.jsonl"

    if [[ ! -f "$ledger_file" ]]; then
        echo "No ledger found"
        exit 0
    fi

    cat "$ledger_file"
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
        review)
            [[ $# -eq 0 ]] && { echo "Error: review requires description"; usage; exit 1; }
            check_requirements
            cmd_review "$@"
            ;;
        propose)
            [[ $# -eq 0 ]] && { echo "Error: propose requires description"; usage; exit 1; }
            check_requirements
            cmd_propose "$@"
            ;;
        rank)
            [[ $# -eq 0 ]] && { echo "Error: rank requires run-id"; usage; exit 1; }
            check_requirements
            cmd_rank "$@"
            ;;
        refine)
            [[ $# -lt 3 ]] && { echo "Error: refine requires run-id, proposal-id, and feedback"; usage; exit 1; }
            check_requirements
            cmd_refine "$@"
            ;;
        decide)
            [[ $# -lt 2 ]] && { echo "Error: decide requires run-id and decision"; usage; exit 1; }
            check_requirements
            cmd_decide "$@"
            ;;
        list)
            cmd_list
            ;;
        show)
            [[ $# -eq 0 ]] && { echo "Error: show requires run-id"; usage; exit 1; }
            cmd_show "$@"
            ;;
        ledger)
            cmd_ledger
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
