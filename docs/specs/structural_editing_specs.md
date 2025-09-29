# Structural Editing Test Cases (2025-09-29)

Purpose: spell out user-facing tree transformations so we can codify goldens for the kernel. Each scenario lists the triggering intent/hotkey, the structural pre-/post-condition, and authoritative code/tests in Athens, Logseq, Slate, or ProseMirror.

## Quick Matrix

| Intent | Athens behaviour | Logseq behaviour |
| --- | --- | --- |
| Move block up (`mod+shift+↑`¹) | Reorders within parent; no implicit reparenting. | Reorders, or hoists under uncle when first child, auto-expands collapsed drop targets, updates page metadata. |
| Move block down (`mod+shift+↓`¹) | Reorders or reattaches beneath next sibling. | Symmetric to move-up; falls through to drop under parent’s right sibling when at bottom. |
| Indent (`Tab`) | No-op for first sibling; otherwise moves under previous sibling and opens it if collapsed. | Skips indentation when left sibling missing or first child; opens collapsed parents and preserves multi-select order. |
| Outdent (`Shift+Tab`) | Moves toward grandparent unless blocked by property/root guard rails. | Moves to parent’s parent; if `logical outdent` disabled it drags right siblings to stay attached. |

¹Logseq encodes the desktop binding in `frontend/handler/shortcut.cljs` (mac = `mod+shift+up`, Win/Linux = `alt+shift+up`). Athens maps the same chord in `athens/views/blocks/textarea_keydown.cljs` but reuses the default OS accelerator; behaviour verified via the atomic op tests below.

## Move Block Up (`mod+shift+↑`)

### Context A – Middle sibling inside a parent
```
parent
├─ a
├─ b*
├─ c
└─ d
```
→ Move `b` above `a`.

- **Athens**: builds `:block/move` with `{:relation :before :block/uid a}` and reorders within the same parent; no other side effects.citetest/athens/common_events/atomic_ops/block_move_test.cljc:20-69
- **Logseq**: `move-blocks-up-down` checks that the “left-left” pointer (`(:block/left (:block/left b))`) shares the same parent; it then calls `move-blocks` with `{:sibling? true}` so the block is reinserted before its previous sibling.citefrontend/modules/outliner/core.cljs:760-776

### Context B – First child under parent with previous sibling (classic “indent-less” move-up)
```
uncle
└─ …
parent
└─ child*
```
→ Expected: move `child` under `uncle` (last position).

- **Athens**: there is no dedicated first-child branch in the move-up tests; the current behaviour leaves the block in place (developers rely on explicit Outdent instead). This gap shows up because no atomic op test exercises the scenario, so a regression test should be added before the kernel locks behaviour.citetest/athens/common_events/atomic_ops/block_move_test.cljc:488-604
- **Logseq**: detects `(:db/id left-left) == (:db/id parent)` and flips `{:sibling? false}`, so `move-blocks` reattaches the block as the last child of the parent’s previous sibling.citefrontend/modules/outliner/core.cljs:778-787

### Context C – Parent itself has no previous sibling (top-level first block)
```
page
├─ block*
└─ peer
```
→ Expect no structural change.

- **Athens**: hotkey falls through to no-op because `atomic_ops/make-block-move-op` cannot target a non-existent sibling; tests cover undo/redo to confirm nothing changes.citetest/athens/common_events/atomic_ops/block_move_test.cljc:592-636
- **Logseq**: when `left-left` is `nil`, the function short-circuits (`and up? left-left`) and returns `nil`, leaving the tree unchanged.citefrontend/modules/outliner/core.cljs:772-790

### Context D – Previous sibling is collapsed

- **Athens**: In the indent flow an extra `:block/open` op is inserted, but move-up does **not** auto-expand; users must expand first.citesrc/cljs/athens/events.cljs:1232-1266
- **Logseq**: Before moving into a collapsed sibling, `move-blocks` calls `fix-non-consecutive-blocks` and `build-move-blocks-next-tx`, which reopen and rebalance children so no nodes disappear from view.citefrontend/modules/outliner/core.cljs:742-751

## Move Block Down (`mod+shift+↓`)

### Context E – Middle sibling
```
parent
├─ a
├─ b*
└─ c
```
→ Move `b` beneath `c`.

- **Athens**: applying `{:relation :after :block/uid c}` reshuffles orders `a,c,b`. Tests assert the new `:block/order` values.citetest/athens/common_events/atomic_ops/block_move_test.cljc:171-210
- **Logseq**: when `up?` is false and a right sibling exists, `move-blocks` is invoked with `{:sibling? true}` targeting that node.citefrontend/modules/outliner/core.cljs:792-795

### Context F – Last child; parent has a right sibling
```
parent
└─ last*
uncle
└─ …
```
→ Hoist block into next branch.

- **Athens**: No atomic move-down test covers cross-parent drops; behaviour mirrors drag-and-drop, so we should add a fixture before codifying.citetest/athens/common_events/atomic_ops/block_move_test.cljc:488-604
- **Logseq**: When the block lacks a right sibling, `move-blocks-up-down` queries `get-right-sibling` on the parent and reattaches the block as that sibling’s last child.citefrontend/modules/outliner/core.cljs:795-797

## Indent (`Tab`)

### Context G – Block has a left sibling
```
parent
├─ left
└─ focus*
```
→ Expected: become child of `left`.

- **Athens**: Prevents indent for the first sibling; otherwise builds a composite op (`block-save` + `block/move`) targeting the previous sibling. If previous sibling is collapsed, emits an extra `:block/open` so the drop target becomes visible. Cursor position is preserved via explicit `:set-cursor-position`.citesrc/cljs/athens/events.cljs:1226-1273
- **Logseq**: `indent-outdent-blocks` calculates `blocks'` to skip already-indented siblings, locates the last direct child of the previous sibling, expands it if collapsed, and calls `move-blocks` with `{:sibling? true}` to append.citefrontend/modules/outliner/core.cljs:818-834

### Context H – First sibling (no left neighbour)

Both editors short-circuit and return no transaction; this is a hard guard to keep root order deterministic.citesrc/cljs/athens/events.cljs:1249-1266frontend/modules/outliner/core.cljs:818-834

## Outdent (`Shift+Tab`)

### Context I – Parent is a normal block
```
grand
└─ parent
   └─ focus*
```
→ Move `focus` to sit after `parent` as sibling.

- **Athens**: Guards against property blocks, embeds, and page roots. Otherwise builds `block-save-block-move` targeting the parent’s parent (`:after` relation) and maintains cursor location.citesrc/cljs/athens/events.cljs:1300-1335
- **Logseq**: Moves selected blocks to the parent with `{:sibling? true}`. When “direct outdenting” is active (default), it also gathers right siblings of the last moved block and reattaches them so the subtree stays contiguous.citefrontend/modules/outliner/core.cljs:835-852

### Context J – Parent is the page/root/embed

- **Athens**: `do-nothing?` guard returns true—command is ignored.citesrc/cljs/athens/events.cljs:1312-1335
- **Logseq**: Outdent stops because `move-blocks` detects original position or `move-parents-to-child?`. Result: no transaction.citefrontend/modules/outliner/core.cljs:724-758

## Multi-Selection

- **Athens**: Delegates multi-indent to drag-and-drop code via `:drop-multi/sibling` when selections share a parent; mixed parents are rejected.citesrc/cljs/athens/events.cljs:1274-1299
- **Logseq**: `move-blocks` normalises selections through `get-top-level-blocks`, keeps order stable, and `fix-non-consecutive-blocks` fills any gaps—critical for selection stacks.citefrontend/modules/outliner/core.cljs:733-751

## Insert-Mode Editors (Text-Level Comparison)

### Slate
- `Transforms.moveNodes` moves structural nodes using explicit paths. The sibling reorder test shows `match` filters blocks and `to: [0]` hoists the focus block to the top of the document.citepackages/slate/test/transforms/moveNodes/selection/block-siblings-before.tsx:5-36
- Nested extraction: moving a child block out of its parent uses `to: [1]`, flattening the hierarchy without bespoke move-up logic.citepackages/slate/test/transforms/moveNodes/selection/block-nested-after.tsx:5-39
- Void node handling requires `voids: true`; otherwise operations skip non-text containers.citepackages/slate/test/transforms/moveNodes/voids-true/block.tsx:5-26

### ProseMirror
- The checked-in repo only bundles the demo harness; core command sources live in submodules pulled by `bin/pm install`, which is unavailable offline. The benchmark harness shows that node edits are expressed as positional `Transform.replaceRangeWith` steps, providing a contrast to block-UID based moves.citedemo/bench/type.js:1-26
- Follow-up: fetch `prosemirror-commands` so we can document `joinBackward`, `lift`, and `setBlockType` semantics alongside Slate.

## Coverage Gaps / Next Actions
1. Add Athens atomic tests for first-child move-up/down to confirm intended behaviour before baking kernel expectations.
2. Promote Logseq’s `fix-non-consecutive-blocks` ideas into kernel proposals so multi-selection moves stay minimal.
3. Vendor ProseMirror command sources to compare join/lift semantics directly.

