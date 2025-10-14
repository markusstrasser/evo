# Review Flow MCP Server

Minimal-linear review workflow: **idea → proposals → ranking → decision**

## Architecture

```
┌─────────────────────────────────────┐
│  review-flow MCP                    │
│                                     │
│  Tools:                             │
│   • propose()    → 3 proposals      │
│   • rank()       → calls eval-MCP   │  ┌──────────────────┐
│   • refine()     → spec + tests     │──┤  eval-MCP        │
│   • decide()     → ADR              │  │  (mounted)       │
│   • review_cycle() → full cycle     │  │                  │
│                                     │  │  • compare()     │
│  Resources:                         │  │  • tournament()  │
│   • review://runs/{id}              │  └──────────────────┘
│   • review://runs/{id}/proposals    │
│   • review://runs/{id}/ranking      │
│   • review://ledger                 │
│                                     │
│  Prompts:                           │
│   • quick_review_prompt()           │
│   • refine_spec_prompt()            │
└─────────────────────────────────────┘
```

## Storage

- **JSONL ledger**: `research/review-ledger.jsonl` (append-only provenance)
- **Flat files**: `research/review-runs/{run_id}/` (run metadata, proposals, rankings, specs)

## Usage

### Quick Start (One-Shot)

```python
# Full cycle: generate → rank → decide (if high confidence)
result = await review_cycle(
    description="How should we implement X?",
    auto_decide=True,
    confidence_threshold=0.85
)
```

### Step-by-Step

```python
# 1. Generate proposals
proposals = await propose(
    description="How should we implement X?",
    providers=["gemini", "codex", "grok"]
)

# 2. Rank proposals (tournament evaluation via eval-MCP)
ranking = await rank_proposals(
    run_id=proposals["run_id"],
    auto_decide=False
)

# 3. Optionally refine winner
spec = await refine(
    run_id=proposals["run_id"],
    proposal_id=ranking["winner_id"],
    feedback="Add more tests",
    max_rounds=5
)

# 4. Decide
decision = await decide(
    run_id=proposals["run_id"],
    decision="approve",
    proposal_id=ranking["winner_id"],
    reason="Best balance of simplicity and features"
)
```

### Autonomous Decision-Making

For simple cases, LLMs can autonomously approve high-confidence winners:

```python
# Auto-approve if confidence > 85% and quality gates pass
ranking = await rank_proposals(
    run_id=run_id,
    auto_decide=True,
    confidence_threshold=0.85
)

if ranking["auto_decided"]:
    print(f"Auto-approved: {ranking['winner_id']}")
    print(f"ADR: {ranking['decision']['adr_uri']}")
else:
    print("Needs human review")
    print(f"Confidence: {ranking['confidence']:.2f}")
    print(f"Next: {ranking['next_actions']['approve']}")
```

## Evaluation Criteria

Rankings prioritize:
1. **Simplicity** (HIGHEST) - Solo dev can understand/debug
2. **Debuggability** - Observable state, clear errors
3. **Flexibility** - Can skip stages, run tools independently
4. **Provenance** - Trace proposal → spec → implementation
5. **Quality gates** - Catch bad specs early

Red flags:
- Infinite refinement loops
- Hidden automation
- Complex orchestration
- Tight coupling
- Over-engineering

## Configuration

Add to `.mcp.json`:

```json
{
  "mcpServers": {
    "review-flow": {
      "command": "python",
      "args": ["-m", "mcp.review.server"],
      "env": {
        "GEMINI_API_KEY": "${GEMINI_API_KEY}",
        "OPENAI_API_KEY": "${OPENAI_API_KEY}",
        "GROK_API_KEY": "${GROK_API_KEY}"
      }
    }
  }
}
```

## Dependencies

- `fastmcp` (2.14+)
- `python-dotenv`
- eval-MCP (mounted for tournament evaluation)
- CLI tools: `gemini`, `codex`, `grok` (in `scripts/`)
