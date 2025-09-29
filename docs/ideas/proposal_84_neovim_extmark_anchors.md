# Proposal 84 · Neovim Extmark Anchors for Stable Selections

## Problem
Block selections in Evolver are stored as raw ids and offsets. When edits splice text, we must manually adjust every selection to avoid jumping cursors.

## Inspiration
Neovim’s extmark API lets clients register stable anchors with gravity, namespaces, and callbacks; the editor automatically keeps them up to date through edits.cite/Users/alien/Projects/inspo-clones/neovim/src/nvim/api/extmark.c:932-1014

## Proposed Change
1. Layer a lightweight `kernel.anchors` registry on top of node ids:
   ```clojure
   (anchors/create! {:id selection-id :node focus :offset 0 :gravity :right})
   ```
2. When primitives mutate `:nodes` or text props, call `anchors/splice` helpers (mirror Neovim’s `extmark_splice_cols`) to keep anchors consistent.
3. Expose anchors to adapters so React/Svelte shells can subscribe to stable selection positions.

## Expected Benefits
- Eliminates manual selection bookkeeping in planners, reducing bugs when nodes move.
- Sets groundwork for collaborative editing (anchors can carry namespaces to segregate users).

## Trade-offs
- Requires careful integration with undo/redo so anchors roll back predictably.
- Anchor maintenance adds overhead; we should store anchors in derived state and only update when necessary.

## Roll-out Steps
1. Implement an anchor store keyed by `{node-id offset}` with gravity flags.
2. Teach `place*` and `update-node*` to emit splice events consumed by the anchor store.
3. Update adapters to consult anchors when rendering cursors/selections.
