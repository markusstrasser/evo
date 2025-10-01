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
  --auto              Generate both AUTO-SOURCE-OVERVIEW.md and AUTO-PROJECT-OVERVIEW.md
                      (Used by git post-merge hook)

  --source            Generate source code overview
                      - Uses EXCERPT.md prompt template
                      - Target: src/
                      - Output: timestamped file in docs/overviews/

  --project           Generate project structure overview
                      - Uses PROJECT-GUIDE.md prompt template
                      - Target: . (root, excludes src/, test/, artifacts)
                      - Output: timestamped file in docs/overviews/

  -t, --target PATH   Generate custom overview for specified path
                      - No prompt template used (direct repomix → gemini)
                      - Output: timestamped file in docs/overviews/

OPTIONS:
  -h, --help          Show this help message
  -p, --prompt TEXT   Additional focus/instructions appended to prompt

EXAMPLES:
  $0 --auto                              # Post-merge: generate both overviews
  $0 --source                            # Manual source overview
  $0 --project                           # Manual project overview
  $0 -t src/core/ -p "Focus on indexes"  # Custom: core modules with focus
  $0 -t docs/                            # Custom: documentation overview

OUTPUT:
  - Auto mode: AUTO-SOURCE-OVERVIEW.md, AUTO-PROJECT-OVERVIEW.md (gitignored)
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
APPEND_PROMPT=""

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
    -t|--target)
      MODE="custom"
      if [[ -z "${2:-}" ]]; then
        echo "Error: --target requires an argument" >&2
        exit 1
      fi
      TARGET="$2"
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
    *)
      echo "Error: Unknown option: $1" >&2
      echo "Run with --help for usage" >&2
      exit 1
      ;;
  esac
done

# Validate mode
if [[ -z "$MODE" ]]; then
  echo "Error: No mode specified. Use --auto, --source, --project, or -t <path>" >&2
  echo "Run with --help for usage" >&2
  exit 1
fi

# Auto mode: generate both and exit
if [[ "$MODE" == "auto" ]]; then
  echo "📦 Auto Mode: Generating dual overviews..."
  echo ""

  echo "🔍 Generating AUTO-SOURCE-OVERVIEW.md..."
  "$0" --source > AUTO-SOURCE-OVERVIEW.md 2>&1
  echo "✓ AUTO-SOURCE-OVERVIEW.md generated"
  echo ""

  echo "📂 Generating AUTO-PROJECT-OVERVIEW.md..."
  "$0" --project > AUTO-PROJECT-OVERVIEW.md 2>&1
  echo "✓ AUTO-PROJECT-OVERVIEW.md generated"

  exit 0
fi

# Set target and prompt based on mode
case "$MODE" in
  source)
    TARGET="src/"
    PROMPT_FILE="EXCERPT.md"
    ;;
  project)
    TARGET="."
    PROMPT_FILE="PROJECT-GUIDE.md"
    ;;
  custom)
    # Target already set via -t
    PROMPT_FILE=""
    ;;
esac

# Generate timestamp and output filename
TIMESTAMP=$(date '+%Y-%m-%d-%H-%M')
TARGET_SANITIZED=$(echo "$TARGET" | sed 's/[^a-zA-Z0-9._-]/-/g' | sed 's/--*/-/g' | sed 's/^-//' | sed 's/-$//')
mkdir -p "docs/overviews"
OUTPUT_FILE="docs/overviews/${TIMESTAMP}-${TARGET_SANITIZED}-overview.md"

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

# Expand glob if needed
TARGET_EXPANDED=($(eval echo "$TARGET"))

if [[ ${#TARGET_EXPANDED[@]} -eq 1 ]] && [[ -d "${TARGET_EXPANDED[0]}" ]]; then
  # Directory: use repomix
  echo "   Method: repomix (directory)"

  if [[ "$TARGET" == "." ]]; then
    # Root mode: exclude code and artifacts
    repomix --copy --output /dev/null \
      --ignore "src/**,test/**,out/**,target/**,node_modules/**,.git/**,.shadow-cljs/**,agent/**" \
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

# Step 3: Process with gemini
echo "3️⃣  Processing with gemini..."
cat "$TEMP_PROMPT" | gemini -y > "$OUTPUT_FILE"

# Cleanup
rm -f "$TEMP_CONTENT" "$TEMP_PROMPT"

echo ""
echo "✅ Overview generated: $OUTPUT_FILE"
