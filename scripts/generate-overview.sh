#!/usr/bin/env bash
set -euo pipefail

# AI Repository Information Script
# Generate architectural overview using repomix/bat and gemini
# Usage: ./scripts/generate-overview.sh [OPTIONS] [SECTIONS...]
#
# Examples:
#   ./scripts/generate-overview.sh                              # All sections, src/
#   ./scripts/generate-overview.sh -t src/core/db.cljc          # Single file
#   ./scripts/generate-overview.sh -t "src/core/*.cljc"         # Multiple files (glob)
#   ./scripts/generate-overview.sh -t test/ performance         # test/ dir, perf section
#   ./scripts/generate-overview.sh performance                  # Section by name
#   ./scripts/generate-overview.sh 1 3 5                        # Sections by index
#   ./scripts/generate-overview.sh 1-3                          # Range of sections

# Section mapping: name -> index
declare -A SECTION_MAP=(
  ["data-model"]="1"
  ["db"]="1"
  ["operations"]="2"
  ["ops"]="2"
  ["pipeline"]="3"
  ["interpret"]="3"
  ["validation"]="4"
  ["schema"]="5"
  ["struct"]="6"
  ["structural"]="6"
  ["usage"]="7"
  ["patterns"]="7"
  ["invariants"]="8"
  ["guarantees"]="8"
  ["performance"]="9"
  ["perf"]="9"
)

# Section index -> section block in EXCERPT.md (line ranges)
declare -A SECTION_LINES=(
  ["1"]="116:127"   # Data Model
  ["2"]="129:141"   # Core Operations
  ["3"]="143:151"   # Transaction Pipeline
  ["4"]="153:160"   # Validation Semantics
  ["5"]="162:169"   # Schema Contracts
  ["6"]="171:183"   # Structural Editing Layer
  ["7"]="185:188"   # Usage Patterns
  ["8"]="190:195"   # Key Invariants & Guarantees
  ["9"]="197:205"   # Performance Characteristics
)

# Section index -> section name
declare -A SECTION_NAMES=(
  ["1"]="Data Model (core.db)"
  ["2"]="Core Operations (core.ops)"
  ["3"]="Transaction Pipeline (core.interpret)"
  ["4"]="Validation Semantics"
  ["5"]="Schema Contracts (core.schema - Malli)"
  ["6"]="Structural Editing Layer (core.struct)"
  ["7"]="Usage Patterns"
  ["8"]="Key Invariants & Guarantees"
  ["9"]="Performance Characteristics"
)

show_help() {
  cat <<EOF
AI Repository Information Script

Usage: $0 [OPTIONS] [SECTIONS...]

Generate architectural documentation by extracting codebase with repomix/bat
and processing with Gemini AI. Sections can be specified by name or number.

OPTIONS:
  -h, --help              Show this help message
  -t, --target PATH       Target directory or file(s) (default: src/)
                          - Directory: uses repomix
                          - File(s): uses bat (supports globs)
  -p, --append-prompt TXT Additional prompt text appended at the end
                          (use for focus/specific instructions)

SECTIONS (1-9):
  1  data-model, db          Data Model (core.db)
  2  operations, ops         Core Operations (core.ops)
  3  pipeline, interpret     Transaction Pipeline (core.interpret)
  4  validation              Validation Semantics
  5  schema                  Schema Contracts (core.schema - Malli)
  6  struct, structural      Structural Editing Layer (core.struct)
  7  usage, patterns         Usage Patterns
  8  invariants, guarantees  Key Invariants & Guarantees
  9  performance, perf       Performance Characteristics

EXAMPLES:
  $0                                         Generate all sections from src/
  $0 -t src/core/db.cljc performance        Performance section for single file
  $0 -t "src/core/*.cljc" 1-3               Sections 1-3 for core modules
  $0 -t test/ validation                     Validation section for test dir
  $0 performance                             Performance section from src/
  $0 1 3 5                                   Sections 1, 3, 5 from src/
  $0 data-model ops                          Data model and ops from src/
  $0 -p "Focus on async patterns" pipeline  Pipeline with specific focus
  $0 -t src/core/ -p "Explain indexes" 1    Data model with focus on indexes

OUTPUT:
  Saves to docs/overviews/YYYY-MM-DD-HH-MM-overview.md and prints to stdout
EOF
}

# Parse arguments
TARGET="src/"
APPEND_PROMPT=""
REQUESTED_SECTIONS=()
AUTO_MODE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      show_help
      exit 0
      ;;
    --auto)
      AUTO_MODE=true
      shift
      ;;
    -t|--target)
      if [[ -z "${2:-}" ]]; then
        echo "Error: --target requires an argument" >&2
        exit 1
      fi
      TARGET="$2"
      shift 2
      ;;
    -p|--append-prompt)
      if [[ -z "${2:-}" ]]; then
        echo "Error: --append-prompt requires an argument" >&2
        exit 1
      fi
      APPEND_PROMPT="$2"
      shift 2
      ;;
    *)
      # Section argument
      arg="$1"
      if [[ "$arg" =~ ^[0-9]+-[0-9]+$ ]]; then
        # Range: 1-3
        start="${arg%-*}"
        end="${arg#*-}"
        for ((i=start; i<=end; i++)); do
          REQUESTED_SECTIONS+=("$i")
        done
      elif [[ "$arg" =~ ^[0-9]+$ ]]; then
        # Numeric index
        REQUESTED_SECTIONS+=("$arg")
      else
        # Section name
        lower_arg="${arg,,}"  # lowercase
        if [[ -n "${SECTION_MAP[$lower_arg]:-}" ]]; then
          REQUESTED_SECTIONS+=("${SECTION_MAP[$lower_arg]}")
        else
          echo "Error: Unknown section name '$arg'" >&2
          echo "Run with --help to see available sections" >&2
          exit 1
        fi
      fi
      shift
      ;;
  esac
done

# Default to all sections if none specified
if [[ ${#REQUESTED_SECTIONS[@]} -eq 0 ]]; then
  REQUESTED_SECTIONS=(1 2 3 4 5 6 7 8 9)
fi

# Remove duplicates and sort
REQUESTED_SECTIONS=($(printf '%s\n' "${REQUESTED_SECTIONS[@]}" | sort -nu))

# Validate section numbers
for section in "${REQUESTED_SECTIONS[@]}"; do
  if [[ ! "$section" =~ ^[1-9]$ ]]; then
    echo "Error: Invalid section number '$section' (must be 1-9)" >&2
    exit 1
  fi
done

# Generate timestamp and output filename
TIMESTAMP=$(date '+%Y-%m-%d-%H-%M')

if [[ "$AUTO_MODE" == "true" ]]; then
  # Auto mode: output to AUTO-PROJECT-OVERVIEW.md at project root
  OUTPUT_FILE="AUTO-PROJECT-OVERVIEW.md"
else
  # Manual mode: timestamped files in docs/overviews/
  TARGET_SANITIZED=$(echo "$TARGET" | sed 's/[^a-zA-Z0-9._-]/-/g' | sed 's/--*/-/g' | sed 's/^-//' | sed 's/-$//')
  mkdir -p "docs/overviews"
  OUTPUT_FILE="docs/overviews/${TIMESTAMP}-${TARGET_SANITIZED}-overview.md"
fi
TEMP_PROMPT="/tmp/gemini-prompt-${TIMESTAMP}.txt"
TEMP_INSTRUCTIONS="/tmp/excerpt-filtered-${TIMESTAMP}.md"
TEMP_CONTENT="/tmp/content-${TIMESTAMP}.txt"

echo "📦 AI Repository Information Script"
echo "   Target: ${TARGET}"
echo "   Sections: ${REQUESTED_SECTIONS[*]}"
echo "   Timestamp: ${TIMESTAMP}"
echo ""

# Step 1: Extract content based on target type
echo "1️⃣  Extracting content..."

# Expand glob patterns
TARGET_EXPANDED=($(eval echo "$TARGET"))

if [[ ${#TARGET_EXPANDED[@]} -eq 1 ]] && [[ -d "${TARGET_EXPANDED[0]}" ]]; then
  # Single directory - use repomix
  echo "   Method: repomix (directory)"
  repomix --copy --output /dev/null --include "${TARGET}**" > /dev/null 2>&1
  pbpaste > "$TEMP_CONTENT"
elif [[ ${#TARGET_EXPANDED[@]} -ge 1 ]]; then
  # File(s) - use bat
  echo "   Method: bat (${#TARGET_EXPANDED[@]} file(s))"
  # Check if bat is available
  if ! command -v bat &> /dev/null; then
    echo "Error: bat not found. Install with 'brew install bat' or use a directory target." >&2
    exit 1
  fi

  # Validate files exist
  for file in "${TARGET_EXPANDED[@]}"; do
    if [[ ! -f "$file" ]]; then
      echo "Error: File not found: $file" >&2
      exit 1
    fi
  done

  # Use bat with plain style and line numbers
  bat --style=plain --paging=never "${TARGET_EXPANDED[@]}" > "$TEMP_CONTENT"
else
  echo "Error: Target not found: $TARGET" >&2
  exit 1
fi

# Step 2: Build filtered EXCERPT.md instructions
echo "2️⃣  Building filtered instructions..."

# Extract base content (everything before "## Sections to Include")
sed -n '1,102p' EXCERPT.md > "$TEMP_INSTRUCTIONS"

# Add "Sections to Include" header
echo "" >> "$TEMP_INSTRUCTIONS"
echo "## Sections to Include (in order)" >> "$TEMP_INSTRUCTIONS"
echo "" >> "$TEMP_INSTRUCTIONS"

# Always include the Header section (lines 105-114)
sed -n '105,114p' EXCERPT.md >> "$TEMP_INSTRUCTIONS"
echo "" >> "$TEMP_INSTRUCTIONS"

# Add each requested section
for section in "${REQUESTED_SECTIONS[@]}"; do
  line_range="${SECTION_LINES[$section]}"
  start_line="${line_range%:*}"
  end_line="${line_range#*:}"
  sed -n "${start_line},${end_line}p" EXCERPT.md >> "$TEMP_INSTRUCTIONS"
  echo "" >> "$TEMP_INSTRUCTIONS"
done

# Add style guidelines and remaining content (line 207 onwards)
sed -n '207,$p' EXCERPT.md >> "$TEMP_INSTRUCTIONS"

# Step 3: Combine with extracted content
echo "3️⃣  Combining instructions with content..."
{
  echo '<instructions>'
  cat "$TEMP_INSTRUCTIONS"
  echo '</instructions>'
  echo ''
  echo '<codebase>'
  cat "$TEMP_CONTENT"
  echo '</codebase>'

  # Add append-prompt if provided
  if [[ -n "$APPEND_PROMPT" ]]; then
    echo ''
    echo '<additional-focus>'
    echo "$APPEND_PROMPT"
    echo '</additional-focus>'
  fi
} > "${TEMP_PROMPT}"

# Step 4: Pipe to gemini with 30 second timeout
echo "4️⃣  Generating overview with Gemini (timeout: 30s)..."
if timeout 30s bash -c "cat '${TEMP_PROMPT}' | gemini --allowed-mcp-server-names context-prompt content-prompt -y > '${OUTPUT_FILE}' 2>&1"; then
  echo "✅ Overview generated successfully!"
else
  echo "⚠️  Warning: Gemini timed out or failed (continuing anyway)"
fi

# Step 5: Clean up
rm -f "${TEMP_PROMPT}" "${TEMP_INSTRUCTIONS}" "${TEMP_CONTENT}"

# Output the full markdown
echo ""
echo "═══════════════════════════════════════════════════════════════════════════════"
echo "📄 Generated Overview (${#REQUESTED_SECTIONS[@]} sections):"
for section in "${REQUESTED_SECTIONS[@]}"; do
  echo "   ${section}. ${SECTION_NAMES[$section]}"
done
echo "   Output: ${OUTPUT_FILE}"
echo "═══════════════════════════════════════════════════════════════════════════════"
echo ""
cat "${OUTPUT_FILE}"