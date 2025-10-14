#!/usr/bin/env bash
set -euo pipefail

# Refactor Anki codebase with 4 parallel Codex agents
# Each agent focuses on different simplification aspects

TIMESTAMP=$(date +%Y-%m-%d-%H-%M)
OUTPUT_DIR=".architect/results/anki-refactor-${TIMESTAMP}"
mkdir -p "$OUTPUT_DIR"

echo "=== Anki Refactoring Analysis ==="
echo "Output: $OUTPUT_DIR"
echo ""

# Prepare the codebase context
repomix src/lab/anki --include "*.cljc,*.cljs" --copy --output /dev/null > /dev/null 2>&1

PROMPT_BASE="You are reviewing a local-first Anki clone codebase (533 LOC).

CODEBASE:
$(pbpaste)

GOAL: Suggest simplifications that reduce LOC while maintaining all functionality.

Focus on:"

# Agent 1: Data structures and state management
{
  echo "$PROMPT_BASE

1. Data structure simplification - are there redundant fields or overly nested structures?
2. State management - can event sourcing be simpler?
3. Card hashing - is the approach optimal for the use case?

Provide specific refactorings with before/after code." | \
  codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto - \
  > "$OUTPUT_DIR/agent-1-data-structures.md" 2>&1 &
}

# Agent 2: Parsing and card types
{
  echo "$PROMPT_BASE

1. Parsing logic - can QA and cloze parsing be unified?
2. Card type extensibility - how could new types be added with minimal code?
3. Regex patterns - are they optimal?

Provide specific refactorings with before/after code." | \
  codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto - \
  > "$OUTPUT_DIR/agent-2-parsing.md" 2>&1 &
}

# Agent 3: Scheduling algorithm
{
  echo "$PROMPT_BASE

1. Scheduling algorithm - can the mock scheduler be simplified?
2. Date/time handling - platform abstraction opportunities?
3. Review metadata - minimal set of fields needed?

Provide specific refactorings with before/after code." | \
  codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto - \
  > "$OUTPUT_DIR/agent-3-scheduling.md" 2>&1 &
}

# Agent 4: UI and file system integration
{
  echo "$PROMPT_BASE

1. UI components - reduce duplication between QA and cloze review screens?
2. File system operations - simplify async promise chains?
3. Event handler wiring - can manual DOM event binding be cleaner?

Provide specific refactorings with before/after code." | \
  codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto - \
  > "$OUTPUT_DIR/agent-4-ui-fs.md" 2>&1 &
}

# Wait for all agents
echo "Running 4 refactoring agents in parallel..."
wait

echo ""
echo "✓ All agents complete"
echo ""
echo "Results:"
ls -lh "$OUTPUT_DIR"/*.md | awk '{print "  " $9 " (" $5 ")"}'
echo ""
echo "Next: Review suggestions and apply best ideas"
