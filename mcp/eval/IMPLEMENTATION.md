# EVO Eval MCP Implementation

## What We Built

A FastMCP server that provides tournament-based AI judge evaluation with:

1. **Single flexible tool** (`compare_items`) - Not abstract criteria, but full context evaluation prompts
2. **Swiss-Lite tournament** - Efficient pairwise comparison
3. **Quality gates** - R² adherence, split-half reliability, brittleness, dispersion
4. **Debiasing** - Position/recency bias detection, attack success rates
5. **Template resources** - Reusable prompt templates for code/writing comparison

## Architecture

```
┌─────────────────────────────────────────┐
│  Claude Code / MCP Client               │
└────────────┬────────────────────────────┘
             │
             │ MCP Protocol
             │
┌────────────▼────────────────────────────┐
│  FastMCP Server (Python)                │
│  - compare_items tool                   │
│  - compare_multiple tool                │
│  - Template resources                   │
└────────────┬────────────────────────────┘
             │
             │ subprocess call
             │
┌────────────▼────────────────────────────┐
│  Clojure Eval CLI                       │
│  src/dev/eval/cli.clj                   │
└────────────┬────────────────────────────┘
             │
             │
┌────────────▼────────────────────────────┐
│  Core Eval Package                      │
│  - core-v3: Swiss-Lite tournament       │
│  - judges_api: OpenAI/Gemini/Grok       │
│  - prompts_v3: Differential scoring     │
│  - schema_audit: R² coherence           │
│  - report: Quality metrics              │
└─────────────────────────────────────────┘
```

## Key Design Decisions

### 1. Evaluation Prompt as Spec

**Problem**: Abstract criteria like "Simplicity" or "Performance" are meaningless without context.

**Solution**: Single `evaluation_prompt` parameter with full project context:
- Project goals and priorities
- Acceptable tradeoffs
- Red flags to watch for

**Example**:
```python
evaluation_prompt = """
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
"""
```

### 2. Thin Python Wrapper

**Why not rewrite in Python?**
- Existing Clojure eval code works and is tested
- Swiss-Lite tournament logic is complex
- Quality gates are battle-tested

**What the Python layer does**:
- Provides standard MCP interface
- Handles tool parameter validation
- Manages temp files and subprocess calls
- Logs progress via FastMCP Context

### 3. FastMCP for Developer Experience

**Benefits**:
- Built-in debugging support (`fastmcp dev`)
- Automatic schema generation from type hints
- Context object for logging/progress
- Testing via in-memory transport
- Standard MCP protocol implementation

## Files Created

```
mcp/eval/
├── eval_server.py          # FastMCP server implementation
├── test_client.py          # Local testing client
├── pyproject.toml          # uv dependencies
├── README.md               # Usage documentation
├── IMPLEMENTATION.md       # This file
└── .env.example            # API key template

src/dev/eval/
└── cli.clj                 # Command-line wrapper for eval

.mcp.json                   # Project MCP server config
```

## Testing

### 1. Test Templates (No API calls)

```bash
cd mcp/eval
uv run python test_client.py
```

Output:
```
Testing templates...

Available templates: 2
  - eval://templates/code-comparison: code_comparison_template
  - eval://templates/writing-comparison: writing_comparison_template

=== CODE COMPARISON TEMPLATE ===

Project: {project_name}
Context: {project_context}
...
```

### 2. Test with FastMCP Dev Mode

```bash
cd mcp/eval
uv run fastmcp dev eval_server.py
```

This opens an interactive debugger where you can:
- List tools and resources
- Call tools manually
- See logs in real-time

### 3. Test via Claude Code

Once configured in `.mcp.json`, restart Claude Code and use:
```
Use the evo-eval MCP server to compare these two implementations...
```

## Quality Gates

Results include validation metrics:

**✓ VALID** - Ready for publication:
- Schema R² > 0.7 (judges coherent)
- Kendall-τ > 0.8 (stable rankings)
- Brittleness < 2 judges (robust)
- Dispersion β < 7.0 (agreement)

**✗ INVALID** - Review required:
- Low R² → judges not using criteria consistently
- Low τ → rankings unstable
- High brittleness → sensitive to dropped judges
- High dispersion → judges disagree

## Example Usage

### Compare Code Implementations

```python
result = await compare_items(
    left="""
def fibonacci(n):
    if n <= 1:
        return n
    return fibonacci(n-1) + fibonacci(n-2)
""",
    right="""
def fibonacci(n):
    a, b = 0, 1
    for _ in range(n):
        a, b = b, a + b
    return a
""",
    evaluation_prompt="""
Teaching Python to CS101 students.

Goals:
- Clarity over performance
- Show recursion concept
- Easy to trace execution

Tradeoffs:
- OK with slower code for clarity
- Prefer explicit over implicit

Red flags:
- Too many concepts at once
- Hard to trace with debugger
"""
)
```

### Compare Multiple Options

```python
result = await compare_multiple(
    items={
        "recursive": "def fib(n): ...",
        "iterative": "def fib(n): ...",
        "memoized": "@cache\ndef fib(n): ...",
        "generator": "def fib():\n  yield ..."
    },
    evaluation_prompt="...",
    max_rounds=5
)
```

## Next Steps

1. **Test with real judges** - Uncomment test in `test_client.py`
2. **Add more templates** - Create domain-specific prompt templates
3. **Improve EDN parsing** - Replace JSON hack with proper EDN parser
4. **Add caching** - Cache tournament results for faster iterations
5. **Metrics visualization** - Generate charts for quality metrics

## See Also

- `mcp/eval/README.md` - User documentation
- `src/dev/eval/README.md` - Core eval package docs
- FastMCP docs: https://gofastmcp.com
- MCP spec: https://modelcontextprotocol.io
