# evo

Event-sourced UI kernel with declarative operations and generative AI tooling.

## What

Pure data transformation library for UI state management:
- **Event sourcing**: All changes as immutable EDN operations
- **Transaction pipeline**: Normalize → validate → apply → derive
- **REPL-first**: Test and debug in the REPL before editing code
- **AI-native**: LLMs generate and compose operations, not DOM

## Philosophy

**Build → Learn → Extract → Generalize**
Not: Theorize → Propose → Analyze → Repeat

- Solo dev using AI agents as helpers
- Focus on 80/20, not production scale
- Simple over clever
- Correctness over performance
- Observable state, clear errors
- No hidden automation

## Quick Start

```bash
# Start dev server
npm install
npm start

# Connect to REPL
# See: dev/repl/init.clj
```

## Project Structure

```
src/core/           # Pure kernel (db, ops, interpret, schema)
src/shell/          # UI adapters (React components)
test/               # Property-based tests
dev/                # REPL helpers, fixtures, diagnostics
skills/             # Agent workflows (research, visual, debug, diagnostics)
docs/               # Architecture, patterns, gotchas
```

## Development

**For humans**: See `npm run` commands for dev workflow (lint, test, REPL health)

**For agents**: See [CLAUDE.md](CLAUDE.md) for full context and tooling

**Key docs**:
- `CLAUDE.md` - Agent instructions and tooling index
- `VISION.md` - Project philosophy and architectural ideas
- `STYLE.md` - Clojure refactoring patterns
- `docs/REPLICANT.md` - Event handler syntax gotchas

## Core Concepts

**Operations**: Three primitives (`create`, `place`, `update`) compose into higher-level intents

**Transaction pipeline**:
1. Normalize operation format
2. Validate against schema and invariants
3. Apply to canonical DB shape
4. Re-derive indexes (children-by-parent, siblings, etc.)

**Testing**: Property-based tests generate random operations, verify invariants hold

**Debugging**: REPL-first workflow - reproduce in REPL, test fix, apply, verify (30s vs 5min)

## Design Constraints

- No protocols in kernel (just pure functions)
- No async in core (event sourcing is synchronous)
- Canonical DB shape owned by kernel
- Adapters at edges only (normalize/denormalize)
- Framework-agnostic core (swap React for anything)

See [VISION.md](VISION.md) for deeper architectural thinking.
