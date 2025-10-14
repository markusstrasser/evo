# Architectural Proposals Workflow - Implementation Summary

**Date:** 2025-09-30
**Session:** Airtight architectural proposal generation and ranking

## What Was Built

### 1. Core Script: `scripts/architectural-proposals`
Fully automated workflow that:
- Generates 15 parallel proposals (5 questions × 3 providers)
- Collects all proposals
- Runs 2 parallel rankings (Codex + Grok)
- Saves everything organized by timestamp

**Key Features:**
- Parallel execution with proper process management
- Automatic Codex output cleaning (skips reasoning preamble)
- Graceful degradation if API keys missing
- Clear progress output with timestamps
- Error handling and validation

### 2. Architectural Questions: `research/architectural-questions.edn`
Five targeted questions covering:
1. **Kernel** - Core 3-operation model simplification
2. **Indexes** - Derived index architecture
3. **Pipeline** - Transaction pipeline improvements
4. **Extensibility** - Plugin/intent compilation model
5. **DX** - Developer experience and tooling

Each question emphasizes: simplicity, readability, debuggability, expressiveness

### 3. Optimized Ranker Prompt: `research/proposal-ranker-prompt.md`
Comprehensive ranking instructions with:

**Primary Criteria:**
- Simplicity (fewer concepts, easy to explain)
- Readability (self-documenting, clear data flow)
- Debuggability (observable state, clear errors, REPL-friendly)
- Expressiveness (powerful composition, low LoC)

**Secondary Criteria:**
- Novel concepts (if they unlock better expressiveness)
- DX for humans + LLMs (REPL, good errors, AI-friendly)

**Explicitly Ignored:**
- Performance (unless impossible to fix later)
- Feature completeness
- Backwards compatibility

**Output Format:**
- Tiered rankings (S/A/B/C/D)
- Scores (1-10) with detailed reasoning
- Implementation risk assessment
- Top 3 recommendations
- Common themes and red flags

### 4. Grok API Wrapper: `scripts/grok`
Simple curl-based wrapper:
- Uses xAI API with Bearer token auth
- Supports model selection (`grok-4`, `grok-4-latest`, `grok-code-fast-1`)
- Handles stdin/prompt input
- Clean JSON response extraction with jq
- Proper error handling

### 5. Documentation: `research/ARCHITECTURAL_PROPOSALS.md`
Comprehensive guide covering:
- Overview and rationale
- Quick start
- Workflow details (proposal generation + ranking)
- Output structure
- Example usage
- Customization guide
- Tips and troubleshooting
- Integration with REPL

### 6. CLI Reference Updates: `research/CLI_REFERENCE.md`
Added Grok section with:
- Usage patterns
- Model options
- Environment variables
- Gotchas and limitations

### 7. Validation Script: `scripts/validate-proposal-setup`
Pre-flight checks for:
- Required files
- Executable permissions
- CLI tools (gemini, codex, jq)
- API keys
- Output directories

### 8. CLAUDE.md Updates
Added "Architectural Proposals Workflow" section with:
- Quick command reference
- Key features (uses overview not source, covers 5 areas)
- Output location
- Link to full documentation

## Design Decisions

### Why Overview Instead of Source?
**Problem:** Source code biases LLMs toward implementation details and micro-optimizations
**Solution:** Use high-level architectural overview to focus on concepts and patterns
**Benefit:** More architectural thinking, less "rename this variable" suggestions

### Why 5 Questions?
**Coverage:** Each question targets a different system aspect
**Diversity:** Ensures broad exploration space
**Manageability:** 15 total proposals (5×3) is digestible but comprehensive

### Why 3 Providers?
**Gemini:** Fast, broad thinking
**Codex:** Deep reasoning with `model_reasoning_effort=high`
**Grok:** Alternative perspective, different "taste"

**Benefit:** Different LLMs excel at different types of reasoning

### Why 2 Rankings?
**Codex:** Detailed technical evaluation
**Grok:** Alternative perspective, may highlight different aspects
**Benefit:** Cross-validation, reveals what different models prioritize

### Why Parallel Execution?
**Speed:** 15 sequential queries would take ~30-45 minutes
**Parallel:** ~2-5 minutes for all queries
**Safety:** Proper process management with PIDs and wait

## Usage Patterns

### Standard Run
```bash
scripts/architectural-proposals
# Output: research/proposals/YYYY-MM-DD-HH-MM/
```

### Review Results
```bash
LATEST=$(ls -td research/proposals/* | head -1)

# Compare rankings
diff $LATEST/rankings/codex-ranking.md $LATEST/rankings/grok-ranking.md

# Read top proposals
bat $LATEST/proposals/codex-*.md

# Extract top 3
grep -A 5 "Top 3 Recommendations" $LATEST/rankings/*.md
```

### Extract Insights
```bash
# Find high-scoring proposals
grep "Score: [89]" $LATEST/rankings/*.md

# Common themes
grep -A 10 "Common Themes" $LATEST/rankings/*.md
```

## Key Implementation Details

### Codex Output Handling
**Problem:** Codex outputs ~1430 lines of prompt echo + reasoning before actual response
**Solution:** Automatically skip first 1430 lines when collecting proposals for ranking
```bash
proposal_text=$(tail -n +1430 "$proposal_file" 2>/dev/null || cat "$proposal_file")
```

### Graceful Degradation
**Problem:** Missing API keys should not break entire workflow
**Solution:** Check for API keys, skip providers gracefully
```bash
if [[ "$provider" == "grok" && -z "${XAI_API_KEY:-}" ]]; then
  echo "  ⊘ $provider: skipped (no API key)"
  continue
fi
```

### Process Management
**Proper parallel execution:**
```bash
# Launch background processes, save PIDs
for ...; do
  (command) &
  pids+=($!)
done

# Wait for all
wait "${pids[@]}" 2>/dev/null || true
```

## Validation Results

✓ All required files present
✓ Scripts executable
✓ CLI tools installed (gemini, codex, jq)
⚠ XAI_API_KEY not set (user will set)
⚠ research/proposals/ will be auto-created

## Files Created

```
scripts/
├── architectural-proposals (main workflow script)
├── validate-proposal-setup (validation script)
└── grok (xAI API wrapper)

research/
├── architectural-questions.edn (5 question definitions)
├── proposal-ranker-prompt.md (ranking instructions)
├── ARCHITECTURAL_PROPOSALS.md (comprehensive guide)
└── CLI_REFERENCE.md (updated with grok)

.agentlog/
├── session-2025-09-30-21-21.md (battle test learnings)
└── architectural-proposals-workflow.md (this file)

CLAUDE.md (updated with workflow reference)
```

## Next Steps

1. **User should set XAI_API_KEY** if they want Grok proposals
2. **Run validation:** `scripts/validate-proposal-setup`
3. **Run workflow:** `scripts/architectural-proposals`
4. **Review rankings:** Compare Codex vs Grok perspectives
5. **Implement top proposals:** Pick winners and build them

## Key Learnings

### What Worked Well
1. **Validation-first approach** - Catch issues before expensive API calls
2. **Parallel execution** - Massive time savings
3. **Clean separation** - Questions, prompts, scripts all separate
4. **Automatic output handling** - Codex reasoning skipped automatically
5. **Tiered output format** - Easy to find S-tier proposals quickly

### What to Watch
1. **API rate limits** - 15 parallel calls might hit limits
2. **Token costs** - Each proposal + ranking uses significant tokens
3. **Codex reasoning offset** - May need adjustment if format changes
4. **Question quality** - Good questions → good proposals

### Future Improvements
1. **Proper EDN parser** - Current question extraction is hacky
2. **Token counting** - Pre-flight check for prompt size
3. **Incremental runs** - Resume failed proposals
4. **Proposal deduplication** - Detect similar ideas across providers
5. **Interactive ranking** - Let user rank too

## Philosophy Alignment

This workflow embodies project values:

**Data-Driven:**
- Questions are data
- Proposals are derived from questions + context
- Rankings are derived from proposals + criteria

**Observable:**
- All intermediate artifacts saved
- Clear progress output
- Easy to inspect and debug

**Composable:**
- Questions, providers, rankers are pluggable
- Can run subsets (fewer questions, fewer providers)
- Scripts can be composed with other tools

**Debuggable:**
- Validation script catches issues early
- Clear error messages
- Organized output structure
- Easy to trace what went wrong

**Expressive:**
- Single command generates comprehensive review
- Low ceremony, high value
- Natural composition (questions → proposals → rankings)

This is exactly how the project itself should work: simple, clear, powerful.
