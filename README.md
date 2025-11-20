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
npm install
npm start              # ALWAYS use this (prevents stale output errors)

# Wait for: [CLJS] Build completed
# Then open: http://localhost:8080/blocks.html
```

**Important**: `npm run watch` is the day-to-day command (skip the expensive clean step when caches are healthy). Run `npm run clean && npm run watch` only when you suspect stale output.

See [docs/START_HERE.md](docs/START_HERE.md) for the latest onboarding steps and troubleshooting tips.

## Project Structure

```
src/kernel/         # Pure kernel (db, ops, interpret, schema, errors)
src/plugins/        # Intent handlers and derived indexes
src/components/     # Replicant components (Block, Sidebar, Quick Switcher, etc.)
src/shell/          # UI adapters / composition glue
test/               # Unit/property + view/integration tests (CLJS + Playwright)
dev/                # REPL helpers, specs, diagnostics, fixtures
resources/          # FR registry, plugin manifest, demo data, tooling assets
docs/               # Architecture notes, rendering/dispatch reference, testing guides
```

## Development

**For humans**: See `npm run` commands for dev workflow (lint, test, REPL health)

**For agents**: See [CLAUDE.md](CLAUDE.md) for full context and tooling

**Key docs & data**:
- `docs/START_HERE.md` - One-page onboarding runbook
- `docs/DX_INDEX.md` - Living index of every deep-dive doc (specs, testing, tooling)
- `resources/plugins.edn` - Declarative plugin manifest (loaded via `shell.plugin-manifest`)
- `resources/demo-pages.edn` - Seed operations for the Blocks UI demo
- `CLAUDE.md` - Agent instructions and tooling index
- `VISION.md` - Product philosophy and architectural direction
- `docs/RENDERING_AND_DISPATCH.md` - Replicant/Nexus event handling reference

## Core Concepts

**Operations**: Three primitives (`create`, `place`, `update`) compose into higher-level intents

**Transaction pipeline**:
1. Normalize operation format
2. Validate against schema and invariants
3. Apply to canonical DB shape
4. Re-derive indexes (children-by-parent, siblings, etc.)

**Testing**:
- View/unit tiers: `bb test:view` (hiccup) and `bb test:int` (render→action) for <1s feedback
- Full suite: `bb test` or `bb test-watch` when you need everything
- Browser flows: `bb e2e` / `bb test:e2e NAV-BOUNDARY-LEFT-01`
- Spec coverage: `bb lint:fr-tests` (pass `-- --strict` once every test cites :fr/ids)

**Debugging**: REPL-first workflow - reproduce in REPL, test fix, apply, verify (30s vs 5min)

## Design Constraints

- No protocols in kernel (just pure functions)
- No async in core (event sourcing is synchronous)
- Canonical DB shape owned by kernel
- Adapters at edges only (normalize/denormalize)
- Framework-agnostic core (swap React for anything)

See [VISION.md](VISION.md) for deeper architectural thinking.
