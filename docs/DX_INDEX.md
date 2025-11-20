# Evo DX Index

Single landing page for humans and AI agents. Follow the lifecycle order below; each section lists canonical docs plus a short machine-readable map.

## Orientation
- `README.md` – project quick start + constraints.
- `docs/START_HERE.md` – single-page onboarding runbook.
- `VISION.md` – product north star and long-term bets.
- `dev/overviews/` (auto-generated via `bb repomix`) – latest architecture snapshots when present.

## Rendering & Dispatch Core
- `docs/RENDERING_AND_DISPATCH.md` – merged Replicant + Nexus reference (hiccup diffing, lifecycle, dispatch data, effects).

## Feature Specs (Triad system)
- `dev/specs/LOGSEQ_SPEC.md` – Canonical Logseq behavior spec (platform-agnostic).
- `docs/specs/LOGSEQ_PARITY_EVO.md` – Evo overlay (implementation guardrails, gap tracker, testing workflow).
- `docs/specs/logseq_behaviors.md` – Triad entries (keymap slices, intent contracts, scenario ledger rows). Scenario IDs reference tests.

## Testing Stack
- `docs/TESTING_STACK.md` – data-driven testing philosophy, tier mapping, redundancy analysis, and scenario ↔ test matrix.

## Tooling & Debugging
- `docs/PLAYWRIGHT_MCP_TESTING.md` – how to run/extend browser automation.
- `docs/CONTENTEDITABLE_DEBUGGING.md` – DOM debugging tactics.
- `docs/CURSOR_FIX_ATTEMPTS.md` – historical investigation log (kept for context).

## Templates
- `docs/templates/TRIAD.md` – required sections for triad specs (Keymap slice, Intent contract, Scenario ledger, optional user story table).

```edn
{:orientation ["README.md" "docs/START_HERE.md" "VISION.md"]
 :core ["docs/RENDERING_AND_DISPATCH.md"]
 :specs ["dev/specs/LOGSEQ_SPEC.md"
         "docs/specs/LOGSEQ_PARITY_EVO.md"
         "docs/specs/logseq_behaviors.md"]
 :testing ["docs/TESTING_STACK.md"]
 :tooling ["docs/PLAYWRIGHT_MCP_TESTING.md" "docs/CONTENTEDITABLE_DEBUGGING.md" "docs/CURSOR_FIX_ATTEMPTS.md"]
 :templates ["docs/templates/TRIAD.md"]}
```
