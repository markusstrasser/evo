# Editing & Navigation Specifications

**Last Updated:** 2025-11-11

This folder holds the authoritative documentation for matching Logseq’s block editing, navigation, and selection behavior inside Evo.

## Primary References

| File | Description |
|------|-------------|
| `LOGSEQ_SPEC.md` | Canonical behavior contract distilled from Logseq (state machine, key handling, structural edits, cursor lifecycle, and testing expectations). Start here before touching any editor code. |
| `LOGSEQ_PARITY.md` | Rolling gap tracker between Evo and the canonical spec. Each entry links the spec section to Evo’s current behavior and outlines the fix and missing tests. Update this file whenever you discover or close a divergence. |

## Archival Material

Historic research notes and superseded specs live under `archive/` (and `later/` for ideas that were postponed). Do not rely on them for implementation guidance unless you reconcile them with `LOGSEQ_SPEC.md`.

## Contributor Workflow

1. Read `LOGSEQ_SPEC.md` to understand the expected behavior.
2. Check `LOGSEQ_PARITY.md` for known gaps before starting new work.
3. When you fix or discover a deviation, document it in `LOGSEQ_PARITY.md`.
4. Write tests that exercise the scenarios called out in the spec (view tests, intent tests, integration tests, and Playwright where applicable).

Keeping these two files current ensures every future agent works from the same ground truth.

## Spec Inventory (2025-11-15)

| File | Role | Next Action |
|------|------|-------------|
| `LOGSEQ_SPEC.md` | Context-free Logseq reference | Keep as canonical truth source. Update only after re-verifying behavior in `~/Projects/best/logseq`. |
| `LOGSEQ_KEYMAP_MACOS.md` | Context-free keymap extract | Keep updated when Logseq’s `modules/shortcut/config.cljs` changes. |
| `dev/logseq-interaction-edge-cases.md` | Context-free research notes | Lives in `dev/`. Keep for citations; sync sections into `LOGSEQ_SPEC.md` when new behavior is confirmed. |
| `LOGSEQ_PARITY.md` | Gap tracker for Evo | **Out-of-date** (claims zero gaps). Sync with `LOGSEQ_PARITY_REGRESSIONS.md` and current code so readers see the real backlog. |
| `LOGSEQ_PARITY_REGRESSIONS.md` | Active TODO spec | Implements page-scoped navigation, DOM-order left/right, visibility-safe Shift+Click, and Shift+Arrow seeding. Drive current work from here. |
| `LOGSEQ_EDITING_SELECTION_PARITY.md` | Active TODO spec (partially implemented) | Cursor/selection dispatcher work mostly landed, but doc-range + Shift+Click coverage still open. Merge overlapping requirements with `LOGSEQ_PARITY_REGRESSIONS.md` once implemented. |
| `PLUGIN_THICK_THIN_PARITY.md` | Active TODO spec | Tracks move climb semantics, empty list Enter, and drag/drop parity. Needs Logseq verification links before implementation continues. |
| `KEYMAP_ALIGNMENT.md` | Implementation log (+ remaining shortcuts) | Serves as TODO list for missing copy/paste/link shortcuts. Archive once those bindings ship and `LOGSEQ_PARITY.md` records the closure. |
| `later/AUTOCOMPLETE_SPEC.md` | Deferred idea | Not tied to parity; leave parked until autocomplete becomes a priority. |

### Recently archived
- `EDGE_CASES.md` → moved to `archive/EDGE_CASES.md` (content now covered by `LOGSEQ_SPEC.md` + regressions doc).
- `CURSOR_BEHAVIOR.md` → moved to `archive/CURSOR_BEHAVIOR.md` (cursor lifecycle captured in `LOGSEQ_SPEC.md` §2–4).
- `LOGSEQ_PARITY_PRD.md` → moved to `archive/LOGSEQ_PARITY_PRD.md` (product-level narrative superseded by `dev/specs/LOGSEQ_SPEC.md` + `docs/specs/LOGSEQ_PARITY_EVO.md`).
