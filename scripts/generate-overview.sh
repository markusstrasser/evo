#!/usr/bin/env bash
set -euo pipefail

# AI Repository Overview Generator
# Uses repomix + gemini to generate architectural overviews
#
# Usage:
#   ./scripts/generate-overview.sh --auto              # Generate both overviews (for git hooks)
#   ./scripts/generate-overview.sh --source            # Generate source overview only
#   ./scripts/generate-overview.sh --project           # Generate project overview only
#   ./scripts/generate-overview.sh -t path/to/code     # Custom overview (no prompt template)

show_help() {
  cat <<EOF
AI Repository Overview Generator

USAGE:
  $0 [OPTIONS]

MODES:
  --auto              Generate all three overviews (source, project, dev)
                      (Used by git post-merge hook)

  --source            Generate source code overview
                      - Uses AUTO-SOURCE-OVERVIEW-PROMPT.md template
                      - Target: src/
                      - Output: AUTO-SOURCE-OVERVIEW.md (default)

  --project           Generate project structure overview
                      - Uses AUTO-PROJECT-OVERVIEW-PROMPT.md template
                      - Target: . (root, excludes src/, test/, artifacts, docs/research)
                      - Output: AUTO-PROJECT-OVERVIEW.md (default)

  --dev               Generate dev tooling overview
                      - Uses AUTO-DEV-OVERVIEW-PROMPT.md template
                      - Target: dev/, bb/
                      - Output: AUTO-DEV-OVERVIEW.md (default)

  -t, --target PATH   Generate custom overview for specified path
                      - No prompt template used (direct repomix → gemini)
                      - Output: timestamped file in current directory

OPTIONS:
  -h, --help          Show this help message
  -o, --output PATH   Custom output path (overrides default naming)
  -p, --prompt TEXT   Additional focus/instructions appended to prompt
  -m, --model MODEL   Gemini model to use (default: gemini-2.5-pro, fast: gemini-2.5-flash)

EXAMPLES:
  $0 --auto                              # Post-merge: generate all overviews
  $0 --source                            # Manual source overview
  $0 --project                           # Manual project overview
  $0 --dev                               # Manual dev tooling overview
  $0 -t src/core/ -p "Focus on indexes"  # Custom: core modules with focus
  $0 -t docs/                            # Custom: documentation overview

OUTPUT:
  - Auto mode: AUTO-SOURCE-OVERVIEW.md, AUTO-PROJECT-OVERVIEW.md, AUTO-DEV-OVERVIEW.md (gitignored)
  - Manual mode: docs/overviews/YYYY-MM-DD-HH-MM-<target>-overview.md

REQUIREMENTS:
  - repomix (for directory scanning)
  - bat (for file reading, optional)
  - gemini CLI (for AI processing)
EOF
}

# Parse arguments
MODE=""
TARGET=""
OUTPUT_PATH=""
APPEND_PROMPT=""
GEMINI_MODEL="gemini-2.5-pro"

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      show_help
      exit 0
      ;;
    --auto)
      MODE="auto"
      shift
      ;;
    --source)
      MODE="source"
      shift
      ;;
    --project)
      MODE="project"
      shift
      ;;
    --dev)
      MODE="dev"
      shift
      ;;
    -t|--target)
      MODE="custom"
      if [[ -z "${2:-}" ]]; then
        echo "Error: --target requires an argument" >&2
        exit 1
      fi
      TARGET="$2"
      shift 2
      ;;
    -o|--output)
      if [[ -z "${2:-}" ]]; then
        echo "Error: --output requires an argument" >&2
        exit 1
      fi
      OUTPUT_PATH="$2"
      shift 2
      ;;
    -p|--prompt)
      if [[ -z "${2:-}" ]]; then
        echo "Error: --prompt requires an argument" >&2
        exit 1
      fi
      APPEND_PROMPT="$2"
      shift 2
      ;;
    -m|--model)
      if [[ -z "${2:-}" ]]; then
        echo "Error: --model requires an argument" >&2
        exit 1
      fi
      GEMINI_MODEL="$2"
      shift 2
      ;;
    *)
      echo "Error: Unknown option: $1" >&2
      echo "Run with --help for usage" >&2
      exit 1
      ;;
  esac
done

# Validate mode
if [[ -z "$MODE" ]]; then
  echo "Error: No mode specified. Use --auto, --source, --project, --dev, or -t <path>" >&2
  echo "Run with --help for usage" >&2
  exit 1
fi

# Auto mode: generate all three and exit
if [[ "$MODE" == "auto" ]]; then
  echo "📦 Auto Mode: Generating three overviews..."
  echo ""

  echo "🔍 Generating AUTO-SOURCE-OVERVIEW.md..."
  if "$0" --source; then
    echo "✓ AUTO-SOURCE-OVERVIEW.md generated"
  else
    echo "✗ Failed to generate source overview" >&2
    exit 1
  fi
  echo ""

  echo "📂 Generating AUTO-PROJECT-OVERVIEW.md..."
  if "$0" --project; then
    echo "✓ AUTO-PROJECT-OVERVIEW.md generated"
  else
    echo "✗ Failed to generate project overview" >&2
    exit 1
  fi
  echo ""

  echo "🛠️  Generating AUTO-DEV-OVERVIEW.md..."
  if "$0" --dev; then
    echo "✓ AUTO-DEV-OVERVIEW.md generated"
  else
    echo "✗ Failed to generate dev overview" >&2
    exit 1
  fi

  exit 0
fi

# Get absolute path to script directory (for prompt files)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Set target and prompt based on mode
case "$MODE" in
  source)
    TARGET="src/"
    PROMPT_FILE="$PROJECT_ROOT/AUTO-SOURCE-OVERVIEW-PROMPT.md"
    ;;
  project)
    TARGET="."
    PROMPT_FILE="$PROJECT_ROOT/AUTO-PROJECT-OVERVIEW-PROMPT.md"
    ;;
  dev)
    TARGET="dev/,bb/"
    PROMPT_FILE="$PROJECT_ROOT/AUTO-DEV-OVERVIEW-PROMPT.md"
    ;;
  custom)
    # Target already set via -t
    PROMPT_FILE=""
    ;;
esac

# Generate timestamp for temp files
TIMESTAMP=$(date '+%Y-%m-%d-%H-%M')

# Determine output file
if [[ -n "$OUTPUT_PATH" ]]; then
  # Custom output specified
  OUTPUT_FILE="$OUTPUT_PATH"
else
  # Default output based on mode
  case "$MODE" in
    source)
      OUTPUT_FILE="$PROJECT_ROOT/AUTO-SOURCE-OVERVIEW.md"
      ;;
    project)
      OUTPUT_FILE="$PROJECT_ROOT/AUTO-PROJECT-OVERVIEW.md"
      ;;
    dev)
      OUTPUT_FILE="$PROJECT_ROOT/AUTO-DEV-OVERVIEW.md"
      ;;
    custom)
      # Custom mode: timestamped file
      TARGET_SANITIZED=$(echo "$TARGET" | sed 's/[^a-zA-Z0-9._-]/-/g' | sed 's/--*/-/g' | sed 's/^-//' | sed 's/-$//')
      OUTPUT_FILE="${TIMESTAMP}-${TARGET_SANITIZED}-overview.md"
      ;;
  esac
fi

TEMP_CONTENT="/tmp/repomix-content-${TIMESTAMP}.txt"
TEMP_PROMPT="/tmp/gemini-prompt-${TIMESTAMP}.txt"

echo "📦 AI Repository Overview Generator"
echo "   Mode: ${MODE}"
echo "   Target: ${TARGET}"
if [[ -n "$PROMPT_FILE" ]]; then
  echo "   Prompt: ${PROMPT_FILE}"
fi
echo "   Output: ${OUTPUT_FILE}"
echo ""

# Step 1: Extract content
echo "1️⃣  Extracting content..."

# Check if target contains comma-separated directories (for dev mode)
if [[ "$TARGET" == *","* ]]; then
  # Multiple directories: convert to repomix include patterns
  echo "   Method: repomix (multiple directories)"
  IFS=',' read -ra DIRS <<< "$TARGET"
  INCLUDE_PATTERN=""
  for dir in "${DIRS[@]}"; do
    if [[ -n "$INCLUDE_PATTERN" ]]; then
      INCLUDE_PATTERN="${INCLUDE_PATTERN},${dir}**"
    else
      INCLUDE_PATTERN="${dir}**"
    fi
  done
  repomix --copy --output /dev/null --include "$INCLUDE_PATTERN" > /dev/null 2>&1
  pbpaste > "$TEMP_CONTENT"

else
  # Expand glob if needed
  TARGET_EXPANDED=($(eval echo "$TARGET"))

  if [[ ${#TARGET_EXPANDED[@]} -eq 1 ]] && [[ -d "${TARGET_EXPANDED[0]}" ]]; then
    # Directory: use repomix
    echo "   Method: repomix (directory)"

    if [[ "$TARGET" == "." ]]; then
      # Root mode: exclude code, artifacts, and research results (to avoid confusing the model)
      repomix --copy --output /dev/null \
        --ignore "src/**,test/**,out/**,target/**,node_modules/**,.git/**,.shadow-cljs/**,agent/**,docs/research/**,research/**,2025-*-overview.md,AUTO-*.md" \
        > /dev/null 2>&1
    else
      # Normal directory
      repomix --copy --output /dev/null --include "${TARGET}**" > /dev/null 2>&1
    fi

    pbpaste > "$TEMP_CONTENT"

elif [[ ${#TARGET_EXPANDED[@]} -ge 1 ]]; then
  # Files: use bat
  echo "   Method: bat (${#TARGET_EXPANDED[@]} file(s))"

  if ! command -v bat &> /dev/null; then
    echo "Error: bat not found. Install with 'brew install bat'" >&2
    exit 1
  fi

  for file in "${TARGET_EXPANDED[@]}"; do
    if [[ ! -f "$file" ]]; then
      echo "Error: File not found: $file" >&2
      exit 1
    fi
  done

  bat --style=plain --paging=never "${TARGET_EXPANDED[@]}" > "$TEMP_CONTENT"

  else
    echo "Error: Target not found: $TARGET" >&2
    exit 1
  fi
fi

# Step 2: Build prompt
echo "2️⃣  Building prompt..."

if [[ -n "$PROMPT_FILE" ]]; then
  # Use prompt template
  if [[ ! -f "$PROMPT_FILE" ]]; then
    echo "Error: Prompt file not found: $PROMPT_FILE" >&2
    exit 1
  fi

  {
    echo '<instructions>'
    cat "$PROMPT_FILE"
    echo '</instructions>'
    echo ''
    echo '<codebase>'
    cat "$TEMP_CONTENT"
    echo '</codebase>'

    if [[ -n "$APPEND_PROMPT" ]]; then
      echo ''
      echo '<additional-focus>'
      echo "$APPEND_PROMPT"
      echo '</additional-focus>'
    fi
  } > "$TEMP_PROMPT"

else
  # Custom mode: no template, simple prompt
  {
    echo "Generate a concise architectural overview of the following codebase."
    echo "Focus on:"
    echo "- High-level structure and organization"
    echo "- Key modules and their responsibilities"
    echo "- Important patterns and conventions"
    echo "- Data flow and architecture"
    echo ""
    echo "Be terse and technical. Use bullet points and diagrams where helpful."

    if [[ -n "$APPEND_PROMPT" ]]; then
      echo ""
      echo "Additional focus:"
      echo "$APPEND_PROMPT"
    fi

    echo ""
    echo "───────────────────────────────────────────────────────────────────"
    echo ""
    cat "$TEMP_CONTENT"
  } > "$TEMP_PROMPT"
fi

# Step 3: Token count check (prevent quota exhaustion)
PROMPT_SIZE=$(wc -c < "$TEMP_PROMPT")
PROMPT_TOKENS=$((PROMPT_SIZE / 4))  # Rough estimate: 1 token ≈ 4 chars

# Gemini-2.5-flash limit: 1M tokens/min, Pro: 2M tokens/min
if [[ "$GEMINI_MODEL" == *"flash"* ]]; then
  TOKEN_LIMIT=1000000
else
  TOKEN_LIMIT=2000000
fi

if [[ $PROMPT_TOKENS -gt $TOKEN_LIMIT ]]; then
  echo "⚠️  Warning: Prompt size (~${PROMPT_TOKENS} tokens) exceeds ${GEMINI_MODEL} limit (${TOKEN_LIMIT} tokens/min)" >&2
  echo "   Consider using --model gemini-2.5-flash or reducing input size" >&2
fi

# Process with gemini (disable MCP servers for batch processing)
echo "3️⃣  Processing with gemini (~${PROMPT_TOKENS} tokens)..."
cat "$TEMP_PROMPT" | gemini -y --model "$GEMINI_MODEL" --allowed-mcp-server-names > "$OUTPUT_FILE"

# Cleanup
rm -f "$TEMP_CONTENT" "$TEMP_PROMPT"

echo ""
echo "✅ Overview generated: $OUTPUT_FILE"
