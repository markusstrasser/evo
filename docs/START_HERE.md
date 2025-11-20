# Start Here

Single-page orientation for humans and agents picking up Evo for the first time.

## 1. Read the Big Picture

1. `README.md` – repo purpose, quick start, design constraints.
2. `VISION.md` – why the kernel exists and where it is headed.
3. `docs/DX_INDEX.md` – curated index of every deep-dive (rendering, specs, testing, tooling).

## 2. Bring Up the Dev Loop

```bash
npm install
npm run watch    # fast path; falls back to cache-aware clean if needed
# visit http://localhost:8080/blocks.html once [CLJS] Build completed
```

Need a clean-room reset? Run `npm run clean && npm run watch`.

## 3. Load the Reference Stack

- Rendering + dispatch pipeline: `docs/RENDERING_AND_DISPATCH.md`
- Functional requirements + specs: `dev/specs/LOGSEQ_SPEC.md`, `docs/specs/LOGSEQ_PARITY_EVO.md`
- Testing tiers + scenarios: `docs/TESTING_STACK.md`

## 4. Quality Tools & Linting

```bash
# Run all quality checks (same as CI)
npm run lint         # Clojure (clj-kondo) + JS/TS (Biome) + E2E keyboard checks

# Individual checks
npm run lint:clj     # Clojure linting only
npm run lint:js      # JavaScript/TypeScript linting
npm run lint:js:fix  # Auto-fix JS/TS issues
npm run format:js    # Format JS/TS files

# Clojure formatting
bb format:check      # Check formatting
bb format:fix        # Auto-fix formatting

# Spec coverage (Spec-as-Database pattern)
npm run lint:fr           # Audit FR implementation coverage
npm run lint:fr-coverage  # Report FR ↔ test coverage
npm run lint:scenarios    # Verify scenario IDs have tests

# Full quality gate (used in CI)
bb check            # lint + format check + compile
```

## 5. Debugging Tools

**Browser guard** (auto-loaded in dev):
- Validates focus/cursor after keyboard navigation
- Detects cursor resets and DB/DOM mismatches
- See `docs/CONTENTEDITABLE_DEBUGGING.md` for details

**DevTools UI** (intent logs + DB diffs):
- Add `?devtools` to URL to show dev panel
- Tracks all intents with before/after state
- Copy operations and DOM diffs to clipboard

## 6. Agent Tooling

- `CLAUDE.md` – automation-specific guidance (commands, bb tasks, MCP hooks).
- `docs/PLAYWRIGHT_MCP_TESTING.md` – browser automation, reports, and debugging shortcuts.

## 7. When in Doubt

1. Search `docs/` for the topic.
2. If still stuck, run `bb repomix` to generate the latest auto-overview and feed that to your LLM of choice.

This document is intentionally short; keep it updated whenever workflows or file names change so new contributors never hit dead links.
