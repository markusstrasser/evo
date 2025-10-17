---
name: Architect
description: Architectural decision-making workflow using tournament-based proposal generation and ranking. Generates proposals from multiple LLM providers (gemini, codex, grok), ranks them via tournament evaluation, optionally refines with feedback loops, and records decisions as ADRs. Use when exploring architectural alternatives, comparing implementation approaches, or making significant design decisions.
---

# Architect Skill

Minimal-linear review workflow for architectural decision-making: **proposals → tournament → ADR**

## Quick Start

```bash
# Full cycle (generate → rank → optionally decide)
skills/architect/run.sh review "How should we implement event sourcing?"

# Step-by-step workflow
skills/architect/run.sh propose "How should we handle state management?"
skills/architect/run.sh rank <run-id>
skills/architect/run.sh refine <run-id> <proposal-id> "Add more examples"
skills/architect/run.sh decide <run-id> approve <proposal-id> "Best approach"
```

## Commands

### `review` - Full Cycle

One-shot review: generate proposals → rank → present results

```bash
skills/architect/run.sh review "problem description"
```

**Options:**
- `--auto-decide` - Automatically approve if confidence > threshold
- `--confidence 0.85` - Confidence threshold for auto-decision (default: 0.85)

### `propose` - Generate Proposals

Generate proposals from multiple LLM providers in parallel (gemini, codex, grok)

```bash
skills/architect/run.sh propose "problem description"
```

**Options:**
- `--providers gemini,codex,grok` - Specify providers (default: all three)

**Output:**
- `.architect/review-runs/{run-id}/run.json` - Run metadata
- `.architect/review-runs/{run-id}/proposal-{provider}.json` - Individual proposals
- Returns `run_id` for next steps

### `rank` - Rank Proposals

Rank proposals using tournament-based evaluation (via tournament-mcp)

```bash
skills/architect/run.sh rank <run-id>
```

**Options:**
- `--auto-decide` - Auto-approve if confidence > threshold
- `--confidence 0.8` - Confidence threshold (default: 0.8)

**Output:**
- `.architect/review-runs/{run-id}/ranking.json` - Rankings with winner
- Shows next actions: approve, revise, or reject_all

### `refine` - Refine Proposal

Refine a proposal with feedback loops (max 5 rounds)

```bash
skills/architect/run.sh refine <run-id> <proposal-id> "feedback message"
```

**Options:**
- `--max-rounds 5` - Maximum refinement rounds (default: 5)

**Output:**
- `.architect/review-runs/{run-id}/spec.json` - Refined specification
- Validation results for each round

### `decide` - Record Decision

Record final decision as ADR (Architectural Decision Record)

```bash
skills/architect/run.sh decide <run-id> approve <proposal-id> "rationale"
skills/architect/run.sh decide <run-id> reject <proposal-id> "reason"
skills/architect/run.sh decide <run-id> defer "" "needs more research"
```

**Output:**
- `.architect/review-runs/{run-id}/adr-{run-id}.md` - Decision record
- Logs to `.architect/review-ledger.jsonl`

## Evaluation Criteria

Rankings prioritize (in order):

1. **Simplicity** (HIGHEST) - Solo dev can understand/debug easily
2. **Debuggability** - Observable state, clear errors, REPL-friendly
3. **Flexibility** - Can skip stages, run tools independently
4. **Provenance** - Trace proposal → spec → implementation
5. **Quality gates** - Catch bad specs before implementation

Red flags:
- Infinite refinement loops
- Hidden automation
- Complex orchestration (hard to debug when stuck)
- Tight coupling (can't run stages independently)
- Over-engineering (10+ agents, dynamic planning)

## File Structure

All outputs go to `.architect/`:

```
.architect/
├── review-runs/{run-id}/      # Architect workflows
│   ├── run.json              # Metadata
│   ├── proposal-gemini.json  # Proposals from each provider
│   ├── proposal-codex.json
│   ├── proposal-grok.json
│   ├── ranking.json          # Tournament results
│   ├── spec.json            # Refined spec (if refined)
│   └── adr-{run-id}.md      # Decision record
├── reports/{research-id}/     # Research reports
└── review-ledger.jsonl        # Append-only provenance log
```

## Requirements

**CLI Tools:**
- `gemini` - Gemini API CLI wrapper
- `codex` - OpenAI Codex CLI wrapper
- `grok` - xAI Grok CLI wrapper (scripts/grok)
- `tournament-mcp` - Tournament evaluation (optional, uses fallback if unavailable)

**API Keys:**
- `GEMINI_API_KEY`
- `OPENAI_API_KEY`
- `XAI_API_KEY`

**Python:**
- Python 3.10+
- No external dependencies (uses stdlib only)

## Examples

### Explore Multiple Approaches

```bash
# Generate proposals from all providers
skills/architect/run.sh propose "How should we implement undo/redo?"

# Review proposals (stored in .architect/review-runs/{run-id}/)
cat .architect/review-runs/{run-id}/proposal-*.json

# Rank them
skills/architect/run.sh rank {run-id}

# Decide
skills/architect/run.sh decide {run-id} approve {winner-id} "Clear and simple"
```

### Quick Decision

```bash
# Full cycle with auto-decision if confidence > 85%
skills/architect/run.sh review "State management approach" --auto-decide --confidence 0.85
```

### Refine Before Deciding

```bash
# Generate and rank
skills/architect/run.sh propose "API design patterns"
skills/architect/run.sh rank {run-id}

# Refine winner with feedback
skills/architect/run.sh refine {run-id} {winner-id} "Add error handling examples"

# Then decide
skills/architect/run.sh decide {run-id} approve {winner-id} "Complete after refinement"
```

## Integration

### With Tournament-MCP

The skill uses tournament-mcp for ranking if available:

```bash
# Check if tournament-mcp is available
which tournament-mcp

# If not available, ranking will use simplified comparison
```

### With Research Skill

Combine with research for comprehensive analysis:

```bash
# Research existing approaches
skills/research/run.sh explore re-frame "state management patterns"

# Generate proposals informed by research
skills/architect/run.sh propose "State management: re-frame vs reagent"
```

## Troubleshooting

**No API keys:**
- Set `GEMINI_API_KEY`, `OPENAI_API_KEY`, `XAI_API_KEY` in `.env`
- Or export in shell: `export GEMINI_API_KEY="your-key"`

**Tournament-mcp not found:**
- Ranking will use simplified comparison mode
- Install tournament-mcp for full tournament evaluation

**Empty proposals:**
- Check API key validity
- Check CLI tools are in PATH: `which gemini codex`
- Check `.env` is sourced

**Run not found:**
- Verify run ID: `ls .architect/review-runs/`
- Check file exists: `cat .architect/review-runs/{run-id}/run.json`
