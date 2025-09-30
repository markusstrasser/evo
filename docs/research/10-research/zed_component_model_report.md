# Zed Component Model — Field Notes (September 29, 2025)

## High-Level Architecture
- **Registry-first design** — Components implement a lightweight `Component` trait (`/Users/alien/Projects/inspo-clones/zed/crates/component/src/component.rs:93-248`). Registration happens via `inventory::collect!`, so every component self-registers at load time without central lists.
- **Macros enforce contracts** — `#[derive(RegisterComponent)]` (`/Users/alien/Projects/inspo-clones/zed/crates/ui_macros/src/derive_register_component.rs:8-40`) expands to a compile-time assertion that the type implements `Component` and submits a registrar thunk to the inventory.
- **Metadata-rich entries** — Each `ComponentMetadata` stores scope, status, doc summary, preview factory, and sort keys (`component.rs:118-210`). This enables consistent filtering and presentation.

## Component Preview Workspace
- **Workspace integration** — `ComponentPreview` lives as a workspace item (`/Users/alien/Projects/inspo-clones/zed/crates/zed/component_preview.rs:15-120`), meaning previews participate in the same docking system as editors.
- **Filtered views** — The preview builds a list of entries (all components, section headers, scoped groups) and supports search + cursor navigation (`component_preview.rs:180-360`). Uniform list scroll handles keep the highlighted entry visible.
- **Persistence** — Preview settings persist via a small SQLite domain (`component_preview/persistence.rs:6-73`), so category filters and active pages survive restarts.

## Layout & Documentation Tooling
- **`ComponentExample` & `ComponentExampleGroup`** — Layout helpers render consistent cards with titles, descriptions, and preview panes (`/Users/alien/Projects/inspo-clones/zed/crates/component/src/component_layout.rs:1-160`). The pattern encourages example-driven docs.
- **Documented derive** — Components often also derive `Documented` (from the `documented` crate), exposing docstrings as metadata (`content_group.rs:24-100`). Descriptions automatically populate the preview UI.

## Status & Scope Discipline
- **Statuses** — `ComponentStatus` enumerates lifecycle states (Work In Progress → Live → Deprecated) with human-readable descriptions (`component.rs:247-303`).
- **Scopes** — `ComponentScope` carves the catalog into semantic buckets (Layout, Input, Typography, Agent, etc.) (`component.rs:303-344`). These tags are central to navigation and filtering.

## Operational Patterns Worth Adapting
1. **Inventory-backed registries** simplify extension mechanics—no central map to maintain.
2. **Doc metadata attached to code** ensures previews/help stay in lockstep with implementation.
3. **Scope + status** provide orthogonal dimensions for triaging and focusing work.
4. **Workspace-scoped persistence** keeps developer context sticky across sessions with minimal ceremony.
5. **Search-first previews** show how lightweight state (filter text + cursor) yields a fast navigation experience for large catalogs.

## Open Questions for Evo
- How do we persist storybook filters/preferences? Zed’s SQLite approach could translate to EDN or Malli-backed stores (see Proposal 102).
- Do we need status tags (e.g., `:work-in-progress`) for invariants/ops to communicate maturity to agents? (Proposal 103.)
- Can we expose component metadata to LLM tooling so that docs/help/test scaffolds derive from a single registry?
