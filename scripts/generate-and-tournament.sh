#!/usr/bin/env bash
set -euo pipefail

# Generate architectural proposals and run tournament

PROMPT="Generate an implementation proposal for this problem:

## Context: Refactoring Development Tooling with Agent Skills

**Current State:**
We have a ClojureScript/Clojure project (local-first Anki clone with event sourcing) with mixed development tooling:

1. **MCPs (Model Context Protocol servers):**
   - \`architect-mcp\` - Multi-step ADR workflow with persistent state (.architect/)
   - \`tournament-mcp\` - Bradley-Terry ranking for LLM evaluation
   - \`mcp-shadow-dual\` - Clojure REPL integration (stateful, long-running)
   - \`exa\` - Third-party code/web search service

2. **CLI Tools:**
   - \`gemini\`, \`codex\`, \`grok\`, \`opencode\` - LLM provider wrappers for research
   - \`repomix\` - Repo bundling for LLM context
   - Simple bash scripts in \`scripts/\` (grok, generate-overview.sh, quick-test.sh, visual validation scripts)

3. **Complex Workflows (documented in CLAUDE.md):**
   - Research workflow: Query ~/Projects/best/* repos using repomix + LLM CLIs
   - Visual validation: Python scripts for canvas/WebGL analysis
   - REPL-first debugging: Browser console patterns + dev helpers
   - Dev diagnostics: Health checks, environment validation, error catalog

**New Capability: Claude Code Agent Skills**
Skills are filesystem-based, progressive-disclosure resources with three loading levels:
- L1 (Metadata): Always loaded, ~100 tokens/skill - name/description for discovery
- L2 (Instructions): Loaded when triggered, <5k tokens - SKILL.md procedural knowledge
- L3+ (Resources): Loaded as needed, unlimited - bundled scripts/docs/data

Skills run in code execution container with:
- ✅ Filesystem access, bash, code execution, pre-installed packages
- ❌ No network access, no runtime package installation

**The Question:**
How should we refactor our development tooling architecture to optimally leverage Agent Skills?

**Specific Considerations:**
1. Which current tools/workflows are good candidates for Skills conversion?
2. What should remain as MCPs vs CLIs vs Skills?
3. How to handle network-dependent operations (Skills can't call external APIs)?
4. What new Skills should we create?
5. How to structure Skills for progressive disclosure benefits?
6. Should LLM provider CLIs (gemini/codex/grok) become Skills or stay as CLIs?

**Constraints:**
- Solo developer using AI agents as helpers (80/20 rule, not production)
- Research workflow is highest-value, most-used pattern
- Visual validation is complex (Python + domain knowledge)
- MCPs with persistent state (architect, tournament) should likely stay as MCPs
- Skills can't access network (rules out external API calls)
- Current approach works but has repetitive context (research patterns, debugging workflows)

**Goals:**
- Reduce repetitive context in sessions
- Package complex workflows for reuse
- Maintain flexibility for pipes/automation
- Keep simple things simple (don't over-engineer)

Provide:
1. Core approach (2-3 sentences)
2. Key components and their responsibilities
3. Data structures and storage
4. Pros and cons
5. Red flags to watch for

Focus on simplicity and debuggability for a solo developer."

# Create temp dir
TMPDIR=$(mktemp -d)
echo "Working in: $TMPDIR"

# Generate proposals in parallel
echo "🤖 Generating proposals from 3 LLM providers..."

echo "$PROMPT" | aictl --provider xai -m grok-4-latest --no-stream > "$TMPDIR/proposal-grok.txt" 2>&1 &
PID_GROK=$!

echo "$PROMPT" | gemini --model gemini-2.5-flash -y > "$TMPDIR/proposal-gemini.txt" 2>&1 &
PID_GEMINI=$!

echo "$PROMPT" | codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto - > "$TMPDIR/proposal-codex.txt" 2>&1 &
PID_CODEX=$!

# Wait for all
wait $PID_GROK && echo "✓ Grok complete" || echo "✗ Grok failed"
wait $PID_GEMINI && echo "✓ Gemini complete" || echo "✗ Gemini failed"
wait $PID_CODEX && echo "✓ Codex complete" || echo "✗ Codex failed"

# Check outputs
echo ""
echo "📊 Proposal sizes:"
wc -l "$TMPDIR"/proposal-*.txt

# Preview
echo ""
echo "Preview of proposals:"
for f in "$TMPDIR"/proposal-*.txt; do
    echo "--- $(basename $f) ---"
    head -20 "$f"
    echo ""
done

echo "Proposals saved to: $TMPDIR"
echo "Next: Run tournament with these proposals"
