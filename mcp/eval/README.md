# EVO Eval MCP Server

MCP server for tournament-based AI judge evaluation with debiasing.

## Features

- **Flexible Evaluation**: Single tool with customizable evaluation prompts (not abstract criteria)
- **Swiss-Lite Tournament**: Efficient pairwise comparison with Bradley-Terry ranking
- **Quality Gates**: R² adherence, split-half reliability, brittleness, dispersion
- **Debiasing**: Position bias, recency bias, verbosity attack detection
- **Multiple Judges**: Uses gpt-5-codex, gemini-2.5-pro, grok-4 by default

## Installation

```bash
cd mcp/eval
uv sync
```

## Local Testing

Test the server locally using fastmcp's debugging support:

```bash
# Run in development mode
uv run fastmcp dev eval_server.py

# Test with the client
uv run python test_client.py
```

## Usage with Claude Code

Add to your `~/.claude.json` or project `.mcp.json`:

```json
{
  "mcpServers": {
    "evo-eval": {
      "command": "uv",
      "args": [
        "--directory",
        "/Users/alien/Projects/evo/mcp/eval",
        "run",
        "fastmcp",
        "run",
        "eval_server.py"
      ],
      "env": {
        "OPENAI_API_KEY": "${OPENAI_API_KEY}",
        "GEMINI_API_KEY": "${GEMINI_API_KEY}",
        "GROK_API_KEY": "${GROK_API_KEY}"
      }
    }
  }
}
```

## Tools

### `compare_items`

Compare two items using tournament evaluation.

**Parameters:**
- `left` (str): First item text
- `right` (str): Second item text
- `evaluation_prompt` (str): Full context with goals/tradeoffs/red-flags
- `judges` (list[str], optional): Judge providers (default: ["gpt5-codex", "gemini25-pro", "grok-4"])
- `max_rounds` (int, optional): Max tournament rounds (default: 3)

**Example:**

```python
result = await compare_items(
    left=implementation_a,
    right=implementation_b,
    evaluation_prompt="""
This is for a local-first Anki clone with event sourcing.

Project goals:
- Correctness first (this is user study data)
- Easy to debug (event log must be inspectable)
- Simple to reason about (avoid clever tricks)

Acceptable tradeoffs:
- Slower performance for clearer code
- More memory for immutability

Red flags:
- Mutation of shared state
- Complex abstractions without clear benefit

Judge which implementation better fits these priorities.
"""
)
```

**Returns:**

```json
{
  "status": "VALID",
  "ranking": [["left", 2.0], ["right", 0.0]],
  "theta": {"left": 2.0, "right": 0.0},
  "ci": {
    "left": [1.804, 2.196],
    "right": [-0.196, 0.196]
  },
  "tau-split": 1.0,
  "schema-r2": 0.85,
  "brittleness": {"k": 1, "pct": 1.0},
  "dispersion": {
    "beta": 5.0,
    "mean-tau": 1.0,
    "consensus": {"left": 2.0, "right": 0.0}
  },
  "bias-beta": {
    "position": {"left": 0.0, "right": 0.0},
    "recency": {"NEW": 0.0, "OLD": 0.0}
  },
  "asr": {
    "recency": 0.0,
    "provenance": 0.0,
    "verbosity": 0.0
  }
}
```

### `compare_multiple`

Compare multiple items in a tournament.

**Parameters:**
- `items` (dict[str, str]): Dict mapping item IDs to text
- `evaluation_prompt` (str): Full context
- `judges` (list[str], optional): Judge providers
- `max_rounds` (int, optional): Max rounds (default: 5)

## Resources

### `eval://templates/code-comparison`

Template for code comparison prompts.

### `eval://templates/writing-comparison`

Template for writing/content comparison prompts.

## Quality Gates

Results include a `status` field:
- `:VALID` - Passed all quality gates, ready for publication
- `:INVALID` - Failed quality gates, review metrics

Key quality metrics:
- **Schema R²**: Measures judge coherence (criteria explain verdicts)
- **Kendall-τ (split-half)**: Ranking stability
- **Brittleness**: Sensitivity to dropped judges
- **Dispersion (Mallows β)**: Ranking agreement between judges

## Debugging

FastMCP provides excellent debugging support:

```bash
# Run with debug logging
FASTMCP_LOG_LEVEL=DEBUG uv run fastmcp dev eval_server.py

# Test individual tools
uv run python -c "
from eval_server import compare_items
import asyncio

result = asyncio.run(compare_items(
    left='def add(a, b): return a + b',
    right='lambda a, b: a + b',
    evaluation_prompt='Judge readability for beginners'
))
print(result)
"
```

## Architecture

The MCP server is a thin Python wrapper that:
1. Takes evaluation requests via MCP tools
2. Writes items/prompt to temp files
3. Calls the Clojure eval CLI (`src/dev/eval/cli.clj`)
4. Returns parsed results

This keeps the proven Clojure eval logic intact while providing a standard MCP interface.

## See Also

- `src/dev/eval/README.md` - Core evaluation package docs
- `docs/CLAUDE.md` - Project development guide
- FastMCP docs: https://gofastmcp.com
