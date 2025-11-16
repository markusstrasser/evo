# Evo DX Index

Single landing page for humans and AI agents. Follow the lifecycle order below; each section lists canonical docs plus a short machine-readable map.

## Orientation
- `README.md` – project quick start.
- `EVO_ARCHITECTURE_ANALYSIS.md` – high-level system map.
- `VISION.md` – product north star.

## Rendering & Dispatch Core
- `docs/RENDERING_AND_DISPATCH.md` – merged Replicant + Nexus reference (hiccup diffing, lifecycle, dispatch data, effects).

## Feature Specs (Triad system)
- `docs/specs/logseq_behaviors.md` – Logseq parity behaviors (keymap slices, intent contracts, scenario ledger rows). Scenario IDs reference tests.

## Testing Stack
- `docs/TESTING_STACK.md` – data-driven testing philosophy, tier mapping, redundancy analysis, and scenario ↔ test matrix.

## Tooling & Debugging
- `docs/PLAYWRIGHT_MCP_TESTING.md` – how to run/extend browser automation.
- `docs/CONTENTEDITABLE_DEBUGGING.md` – DOM debugging tactics.
- `docs/CURSOR_FIX_ATTEMPTS.md` – historical investigation log (kept for context).

## Templates
- `docs/templates/TRIAD.md` – required sections for triad specs (Keymap slice, Intent contract, Scenario ledger, optional user story table).

```edn
{:orientation ["README.md" "EVO_ARCHITECTURE_ANALYSIS.md" "VISION.md"]
 :core ["docs/RENDERING_AND_DISPATCH.md"]
 :specs ["docs/specs/logseq_behaviors.md"]
 :testing ["docs/TESTING_STACK.md"]
 :tooling ["docs/PLAYWRIGHT_MCP_TESTING.md" "docs/CONTENTEDITABLE_DEBUGGING.md" "docs/CURSOR_FIX_ATTEMPTS.md"]
 :templates ["docs/templates/TRIAD.md"]}
```
