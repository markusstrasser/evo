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
