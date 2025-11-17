# UI Presentation Elegance Spec

**Last Updated:** 2025-11-16  
**Owner:** Frontend/UI Platform

This document captures the scope and requirements for cleaning up Evo’s presentation layer around the sidebar navigation and block embed components. The goal is to make styling declarative (Garden-first), reduce duplication, and eliminate imperative DOM tweaking inside UI render functions.

---

## Background

- `src/components/sidebar.cljs` inlines every visual property (layout, typography, hover colors) and imperatively adjusts hover styles through `:mouseenter` / `:mouseleave` handlers.
- `src/components/block_embed.cljs` repeats the same structural wrapper and style map for each display mode (`normal`, `missing`, `circular`, `max-depth`).
- The repo already depends on `garden/garden`, but these components bypass the shared styling toolchain, increasing component noise and making design tweaks error prone.

---

## Goals

1. **Declarative Styling:** All persistent styles for the sidebar and block embed flows live in Garden namespaces that emit CSS at build time (bundled into `input.css`).
2. **CSS-driven Interactions:** Hover, focus, and selection states rely on CSS selectors (`:hover`, `[data-selected=true]`), not DOM mutation.
3. **Reusable Embeds:** Block embed UI scaffolding is centralized so variant states only provide message/content overrides, not duplicate containers.
4. **DRY Style Tokens:** Shared spacing, typography, and color tokens are defined once and reused for all component states.

---

## Non-Goals

- Re-skinning the sidebar or embed UI beyond aligning the existing visual language.
- Introducing a design system for every component. This spec is scoped to the sidebar navigation and block embed surfaces.
- Reworking underlying data flow (`pages/*`, `Block` component recursion, etc.).

---

## Requirements

### 1. Garden Stylesheets

- Create `src/styles/sidebar.cljs` and `src/styles/block_embed.cljs` (naming flexible but must live under `src/styles/`).
- Each namespace should expose a `defgenerated`/`defstyles` (project convention) that the build already consumes or a helper that returns Hiccup CSS rules for inclusion in the existing Garden pipeline.
- Tokenize common values (padding, radii, colors) via vars or `def ^:const`.
- Output class names:
  - `.sidebar`, `.sidebar__header`, `.sidebar__list`, `.sidebar__item`, `.sidebar__item--current`, `.sidebar__item--empty`.
  - `.embed-container`, modifiers like `.embed-container--normal`, `--missing`, `--circular`, `--max-depth`, plus `.embed-container__icon`, `.embed-container__message`.

### 2. Component Refactors

- Replace inline `:style` maps with `:class` / `:class-name` pointing to the new Garden classes.
- Sidebar:
  - Remove `:mouseenter` / `:mouseleave` handlers. Instead, `:data-selected` (boolean string) must be set so CSS can gate hover states (e.g., only apply hover background when `data-selected="false"`).
  - Keep click handler logic untouched.
  - Optional: convert the 📄 icon span to use a semantic class for consistent spacing via CSS.
- Block Embed:
  - Introduce a helper (e.g., `defn embed-container [variant {:keys [block-id title icon]} & children]`) that wraps content with shared markup.
  - Variants supply icon/message/aria labels; base container adds `:class` and `:data-variant`.
  - Block rendering path simply invokes `(embed-container :normal {...} (when Block ...))`.

### 3. Accessibility & Interactions

- Sidebar items must remain keyboard focusable (current divs need `:role "button"` + `:tab-index 0` or be replaced with `<button>`). Any ARIA changes must be captured in this refactor.
- Hover/focus styles must meet WCAG contrast (≥3:1 against background). Use current colors or adjust tokens if needed.
- Embed warnings should expose `role="alert"` for error states and include descriptive text for screen readers.

### 4. Testing & Validation

- Unit/interaction tests remain unchanged but ensure CLJS build no longer warns about unused inline style keywords.
- Add Storybook/devcards/manual checklist (if applicable) noting:
  - Sidebar hover on non-selected item works via CSS.
  - Selected sidebar item does not flicker when moving mouse quickly.
  - Each embed variant renders with the correct icon/message and shares layout.

---

## Implementation Plan

1. **Scaffold Styles:** Add Garden namespaces, export classes, ensure build emits them (hook into existing Garden compile step or add to `input.css` import).
2. **Sidebar Refactor:**
   - Swap inline styles for classes.
   - Add `data-selected` + semantic roles.
   - Delete imperative hover handlers.
3. **Embed Refactor:**
   - Create `embed-container` helper + variant map.
   - Migrate `BlockEmbed` branches to use helper.
   - Inject Garden classes.
4. **QA:** `npm run shadow cljs watch app` (or relevant target) + manual verification; run existing tests (`bb test` / `npm test` as appropriate).
5. **Docs:** Update this spec’s status and point to commits when complete.

---

## Open Questions

1. Does the current build automatically ingest new Garden namespaces, or do we need to add a compile step to `bb`/`npm` scripts?
2. Are there other components sharing sidebar/embed styles that should be included now to avoid partial migrations?

Document answers here before implementation starts.
