# Research Directory

Archive of research, experiments, and proposals.

## Structure

```
research/
├── proposals/       # Architectural proposals from LLMs
├── papers/          # Paper analysis and meta-processes
├── experiments/     # Experiment results and refactoring attempts
└── README.md       # This file
```

## Proposals

**Path**: `research/proposals/`

LLM-generated architectural proposals for project decisions.

**Contents**:
- `codex-2025-10-13.md` - Codex's proposal for MCP tooling
- `gemini-2025-10-13.md` - Gemini's proposal for MCP tooling
- `context-2025-10-13.md` - Context7 evaluation context

**Workflow**: See `scripts/architectural-proposals` for generating new proposals.

## Papers

**Path**: `research/papers/`

Analysis of research papers and meta-processes.

**Contents**:
- `META_PROCESS.md` - Full lifecycle with self-verification
- `RESEARCH_OUTLINE.md` - Research area outline
- `README.md` - Paper research pipeline docs

**Topics**: AI-native development, multi-agent systems, evaluation methods

## Experiments

**Path**: `research/experiments/`

Results from running experiments, refactorings, and architectural explorations.

**Contents**:
- `anki-refactor-*` - Anki codebase refactoring experiments
- `srs-architecture-*` - SRS system architecture explorations
- `srs-refactoring-*` - SRS refactoring attempts

**Naming**: `{topic}-{date}` format (e.g., `anki-refactor-2025-10-09-21-33`)

## Workflow

### Generate Proposals

```bash
# Generate architectural proposals from 3 LLMs
scripts/architectural-proposals "How should we implement X?"

# Output: research/proposals/{topic}-{date}/
```

### Run Experiments

```bash
# Research best-of repos
scripts/best-repos-research "How do they implement X?"

# Output: research/experiments/{topic}-{date}/
```

### Analyze Papers

```bash
# Run paper research pipeline
scripts/research-mcp-tooling

# Output: research/papers/{topic}/
```

## Archive Policy

- Keep experiments for 3 months minimum
- Archive to `research/archive/` after 6 months
- Delete after 1 year if not referenced

## See Also

- `scripts/architectural-proposals` - Proposal generation
- `scripts/best-repos-research` - Parallel research queries
- `scripts/analyze-battle-test` - Result synthesis
- `CLAUDE.md` - Research workflow docs
