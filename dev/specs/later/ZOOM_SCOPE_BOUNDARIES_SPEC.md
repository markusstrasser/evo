# Zoom/Fold Scope Boundaries (Deferred)

**Status**: Deferred - requires zoom feature to be functional in blocks-ui shell first

## Overview

Enforce zoom/fold boundaries for structural commands to match Logseq parity. When zoomed into a block Z, any operation that would move blocks outside of Z should be a no-op.

## Related Specs

- LOGSEQ_SPEC.md §7 (Fold & Zoom Constraints)
- LOGSEQ_PARITY_PRD.md §5.3
- LOGSEQ_PARITY.md G-Scope-01

## Functional Requirements

### FR-Scope-01: Navigation stays within visible outline
- Arrow navigation cannot escape current page or zoom root
- Focus wraps within visible scope
- Already implemented for navigation (selection/navigation queries use visible-blocks-in-dom-order)

### FR-Scope-02: Structural operations blocked by zoom boundary
When zoomed into block Z, the following operations must be no-ops if they would move a block outside Z:
- **Outdent (Shift+Tab)**: Cannot outdent if grandparent is outside zoom
- **Move Up/Climb (Cmd+Shift+Up)**: Cannot climb if target parent level is outside zoom
- **Move Down/Descend (Cmd+Shift+Down)**: Cannot descend if target (parent's next sibling) is outside zoom
- **Shift+Arrow extend**: Cannot extend selection beyond visible outline

### FR-Scope-03: Zoom out restores normal scope
- Cmd+, (zoom out) should restore normal page-level scope
- All operations should work normally after zoom out

## Implementation Notes

### Core Logic (for when zoom works)

Add a helper in `src/plugins/struct.cljc`:

```clojure
(defn- within-zoom-scope?
  "Check if target-id is within the current zoom scope.

   When zoomed into block Z, any node N is within scope if:
   - N is Z itself, OR
   - N is a descendant of Z

   When not zoomed (zoom-root is nil), all nodes are in scope."
  [db target-id]
  (if-let [zoom-root (q/zoom-root db)]
    (or (= target-id zoom-root)
        (db/descendant-of? db zoom-root target-id))
    true))
```

Update these operations to check zoom scope:
- `outdent-ops`: Check if grandparent is within scope before allowing outdent
- `move-selected-up-ops`: Check if climb target (grandparent) is within scope
- `move-selected-down-ops`: Check if descend target (parent's next sibling) is within scope

### View Layer Fix

The blocks-ui shell's `Outline` component needs to respect zoom-root:

```clojure
;; In src/shell/blocks_ui.cljs App component:
(let [db @!db
      current-page-id (pages/current-page db)
      zoom-root (q/zoom-root db)  ; Get zoom-root if zoomed
      page-title (when current-page-id (pages/page-title db current-page-id))]
  ;; ...
  (Outline {:db db
            :root-id (or zoom-root current-page-id)  ; Use zoom-root when zoomed
            :on-intent handle-intent}))
```

## Why Deferred

During implementation (Nov 2025), discovered that:
1. The zoom feature (`Cmd+.`) doesn't work in the blocks-ui shell
2. Keyboard shortcut `Meta+Period` in Playwright tests doesn't trigger zoom-in intent
3. View layer doesn't render zoomed state even when zoom-root is set in DB

**Prerequisites before resuming**:
- Fix zoom-in/zoom-out keyboard shortcuts in blocks-ui shell
- Ensure view layer respects zoom-root when rendering
- Verify zoom works manually in browser before writing E2E tests

## Unit Tests (Ready to Use)

When zoom is functional, add these tests to `test/plugins/struct_test.cljc`:

```clojure
(deftest outdent-blocked-by-zoom-boundary
  (let [db (build-zoomed-doc)  ; Helper that sets zoom-root to "parent"
        ;; Try to outdent child-a (would move it to page level, outside zoom)
        ops (struct/outdent-ops db "child-a")]
    ;; Should be no-op (empty operations list)
    (is (empty? ops))))

(deftest move-up-climb-blocked-by-zoom-boundary
  (let [db (build-zoomed-doc)
        ops (struct/move-selected-up-ops db {:selection #{"child-a"}})]
    ;; Climb would move child-a to page level (outside zoom) - should be no-op
    (is (empty? ops))))

(deftest move-down-descend-blocked-by-zoom-boundary
  (let [db (build-zoomed-doc)
        ops (struct/move-selected-down-ops db {:selection #{"child-b"}})]
    ;; Descend would try to move into next sibling of parent (outside zoom) - no-op
    (is (empty? ops))))

(deftest operations-allowed-within-zoom-scope
  ;; When operating within zoom scope, operations should work normally
  ;; (test with nested structure where parent has children and grandchildren)
  )
```

## E2E Test Scenarios (Ready to Use)

When zoom is functional, create `test/e2e/scope-boundaries-parity.spec.js`:

### Test Structure
1. Helper: `zoomIntoBlock(page, blockId)` - Click block, press `Cmd+.`, verify zoom
2. Helper: `getVisibleBlockIds(page)` - Return unique block IDs in DOM (deduplicate since `data-block-id` appears on container + content span)
3. Helper: `getBlockStructure(page)` - Return parent-child relationships from DOM

### Scenarios
- **Outdent blocked**: Shift+Tab on block inside zoom is no-op when grandparent outside
- **Outdent works**: Shift+Tab within zoom scope works normally
- **Climb blocked**: Cmd+Shift+Up on first child is no-op when climb target outside zoom
- **Climb works**: Cmd+Shift+Up within zoom scope works normally
- **Descend blocked**: Cmd+Shift+Down on last child is no-op when descend target outside
- **Descend works**: Cmd+Shift+Down within zoom scope works normally
- **Navigation**: ArrowDown respects zoom root boundary
- **Zoom out**: Cmd+, restores normal scope and operations

## Estimated Effort

- **Implementation**: 2-3 hours (once zoom feature works)
- **Testing**: 1-2 hours
- **Total**: Half a day

## Notes

This work was attempted on Nov 15-16, 2025. The core logic is sound (unit test approach verified), but blocked on zoom feature not working in the UI. Revisit after zoom is functional.

Related commit (reverted): Look for "zoom boundary" in git history around Nov 2025.
