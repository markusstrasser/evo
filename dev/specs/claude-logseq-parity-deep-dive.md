# Logseq Parity Deep Dive: Highlighting, Outdenting, and Deselection

**Date:** 2025-01-09
**Author:** Claude (Deep Analysis)
**Status:** 🔴 Critical Issues Found
**Priority:** P0 - Core UX differences from Logseq

---

## Executive Summary

Deep analysis of evo's implementation vs Logseq (macOS) reveals **critical behavioral differences** in three core features:

1. **✅ Highlighting (Cmd+Shift+H):** Mostly correct, minor verification needed
2. **❌ Outdenting (Shift+Tab):** WRONG - Implements non-standard behavior not in Logseq
3. **⚠️ Deselection:** PARTIAL - Missing empty-click and Escape behaviors

### User Configuration Context

User's Logseq config (`~/Documents/context/logseq/config.edn:423`):
```edn
:editor/logical-outdenting? true
```

**This means:** User expects **logical outdenting** behavior, not direct outdenting.

---

## 1. HIGHLIGHTING (Cmd+Shift+H)

### Current Implementation Status: ✅ MOSTLY CORRECT

**Location:** `src/plugins/text_formatting.cljc`

#### Keybinding
```clojure
[{:key "h" :shift true :mod true} {:type :format-selection :marker "^^"}]
```
- **macOS:** Cmd+Shift+H
- **Linux/Win:** Ctrl+Shift+H

#### Behavior Flow

1. **Selection Required:** User must select text first
2. **DOM Selection Capture:** `shell/blocks_ui.cljs:159-173` reads selection from DOM
3. **Intent Enrichment:**
   ```clojure
   {:type :format-selection
    :block-id "block-123"
    :start 5
    :end 12
    :marker "^^"}
   ```
4. **Toggle Logic:** `plugins/text_formatting.cljc:6-27`
   - Already wrapped → Unwrap
   - Not wrapped → Wrap
5. **Selection Preservation:** Stores `:pending-selection` to reselect text after formatting
6. **DOM Restoration:** Watcher applies selection in next frame (`blocks_ui.cljs:358-378`)

#### Example

**Before:**
```
Block text: "hello world test"
Selection: [0, 5] → "hello"
```

**User presses Cmd+Shift+H:**

**After (wrapped):**
```
Block text: "^^hello^^ world test"
Selection: [2, 7] → "hello" (preserved inside markers)
```

**User presses Cmd+Shift+H again:**

**After (unwrapped):**
```
Block text: "hello world test"
Selection: [0, 5] → "hello" (preserved)
```

#### ✅ What's Correct
- Toggle on/off works
- Marker syntax `^^text^^` matches Logseq
- Selection preservation implemented

#### ⚠️ Verification Needed
- [ ] Test in actual Logseq: Does selection stay after highlighting?
- [ ] Test edge case: Partially overlapping highlights
- [ ] Test: Highlight at block boundaries (start/end)

#### Logseq Expected Behavior

**Assumption:** Logseq uses same toggle logic with selection preservation.

**Manual Test Protocol:**
1. In Logseq: Select text "hello"
2. Press Cmd+Shift+H
3. Observe: Is text still selected?
4. Press Cmd+Shift+H again
5. Verify: Unwraps correctly?

---

## 2. OUTDENTING (Shift+Tab)

### Current Implementation Status: ❌ CRITICAL ISSUE - WRONG BEHAVIOR

**Location:** `src/plugins/struct.cljc:51-87`

#### Problem Statement

Evo implements **"right siblings become children"** behavior, which:
- ❌ Is NOT in Logseq (neither mode)
- ❌ Is NOT in Roam Research
- ❌ Was briefly in Workflowy (2017) but **reverted due to user complaints**

The code comment claims "Logseq/Roam/Workflowy style" but this is **factually incorrect**.

#### Evo's Current (Incorrect) Behavior

**Before:**
```
- Parent
  - Child A
  - Child B  ← outdent this
  - Child C
  - Child D
```

**After (evo's implementation):**
```
- Parent
  - Child A
- Child B  ← outdented
  - Child C  ← ❌ became child of B (WRONG)
  - Child D  ← ❌ became child of B (WRONG)
```

**Code causing this:**
```clojure
;; src/plugins/struct.cljc:83-86
;; NEW: Move right siblings to become children of outdented block (Direct Outdenting)
(mapv (fn [sibling-id]
        {:op :place :id sibling-id :under id :at :last})
      right-siblings)
```

#### Logseq Outdenting Modes

Logseq has TWO distinct modes controlled by `:editor/logical-outdenting?`

##### Mode 1: Direct Outdenting (`:editor/logical-outdenting? false`)

**Behavior:** Block moves out, **stays in vertical position**, siblings **unchanged**

**Before:**
```
- Parent
  - Child A
  - Child B  ← outdent this
  - Child C
  - Child D
```

**After:**
```
- Parent
  - Child A
- Child B  ← moved here, right after Parent
  - Child C  (unchanged depth)
  - Child D  (unchanged depth)
```

**Visual:** Block "pops out" horizontally but stays roughly in same vertical spot.

##### Mode 2: Logical Outdenting (`:editor/logical-outdenting? true`) ← USER'S SETTING

**Behavior:** Block moves out, **goes to bottom** of parent's children, siblings **unchanged**

**Before:**
```
- Parent
  - Child A
  - Child B  ← outdent this
  - Child C
  - Child D
```

**After:**
```
- Parent
  - Child A
  - Child C  (moved up)
  - Child D  (moved up)
- Child B  ← moved to bottom (after all of Parent's children)
```

**Visual:** Block "jumps down" to end of parent's children list.

#### Historical Context

From Workflowy discussion (2017):
> "shift-tab now works the way it did before"

Workflowy **removed** the "right siblings become children" behavior after user feedback. Some power users wanted it as a toggle, but it was deemed too confusing as default behavior.

#### Specification: Correct Implementation

##### Logical Outdenting Algorithm

```clojure
(defn outdent-ops-logical
  "Logical outdenting: block moves to bottom of grandparent's children.
   Right siblings remain at original level."
  [db id]
  (let [p (q/parent-of db id)
        gp (when p (q/parent-of db p))
        roots (set (:roots db const/roots))]
    (if (and p gp (not (contains? roots gp)))
      ;; Move block to end of grandparent's children
      [{:op :place :id id :under gp :at :last}]
      [])))
```

**No sibling manipulation!**

##### Direct Outdenting Algorithm

```clojure
(defn outdent-ops-direct
  "Direct outdenting: block moves right after parent.
   Right siblings remain at original level."
  [db id]
  (let [p (q/parent-of db id)
        gp (when p (q/parent-of db p))
        roots (set (:roots db const/roots))]
    (if (and p gp (not (contains? roots gp)))
      ;; Move block right after parent
      [{:op :place :id id :under gp :at {:after p}}]
      [])))
```

**No sibling manipulation!**

#### Implementation Plan

**File:** `src/plugins/struct.cljc`

1. **Add config setting** (or hardcode logical mode for now):
   ```clojure
   (def ^:dynamic *use-logical-outdenting* true)
   ```

2. **Replace `outdent-ops` function:**
   ```clojure
   (defn outdent-ops
     "Outdent node to be sibling of parent.
      Mode determined by *use-logical-outdenting*:
      - true (logical): block moves to bottom of grandparent's children
      - false (direct): block moves right after parent

      Right siblings are NEVER affected."
     [db id]
     (let [p (q/parent-of db id)
           gp (when p (q/parent-of db p))
           roots (set (:roots db const/roots))]
       (if (and p gp (not (contains? roots gp)))
         [{:op :place
           :id id
           :under gp
           :at (if *use-logical-outdenting*
                 :last                    ; Logical: go to end
                 {:after p})}]            ; Direct: right after parent
         [])))
   ```

3. **Remove sibling manipulation code** (lines 83-86)

4. **Update docstring** to reflect correct behavior

#### Test Impact

**File:** `test/e2e/critical-fixes.spec.js`

##### ❌ Test to DELETE/REWRITE: Lines 136-197

```javascript
test('outdenting makes right siblings into children', async ({ page }) => {
  // ...
  // CRITICAL: Child C and D should be more indented than before (became children of B)
  expect(childCAfter.depth).toBeGreaterThan(before.find(b => b.text === 'Child C').depth);
  expect(childDAfter.depth).toBeGreaterThan(before.find(b => b.text === 'Child D').depth);
```

**This test validates WRONG behavior.**

##### ✅ New Test: Logical Outdenting

```javascript
test('logical outdenting moves block to bottom, siblings unchanged', async ({ page }) => {
  // Create structure:
  // - Parent
  //   - Child A
  //   - Child B ← will outdent this
  //   - Child C
  //   - Child D

  await page.keyboard.press('Meta+a');
  await page.keyboard.press('Backspace');

  await page.keyboard.type('Parent');
  await page.keyboard.press('Enter');
  await page.keyboard.type('Child A');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await page.keyboard.type('Child B');
  await page.keyboard.press('Enter');
  await page.keyboard.type('Child C');
  await page.keyboard.press('Enter');
  await page.keyboard.type('Child D');

  await page.waitForTimeout(200);

  // Get structure before outdenting
  const before = await getTreeStructure(page);
  const childBBefore = before.find(b => b.text === 'Child B');
  const childCBefore = before.find(b => b.text === 'Child C');
  const childDBefore = before.find(b => b.text === 'Child D');

  // Navigate to Child B and outdent
  await page.keyboard.press('ArrowUp');  // To Child C
  await page.keyboard.press('ArrowUp');  // To Child B
  await page.keyboard.press('Shift+Tab');

  await page.waitForTimeout(300);

  const after = await getTreeStructure(page);
  const childBAfter = after.find(b => b.text === 'Child B');
  const childCAfter = after.find(b => b.text === 'Child C');
  const childDAfter = after.find(b => b.text === 'Child D');

  // LOGICAL OUTDENTING ASSERTIONS:

  // 1. Child B should be outdented (less depth)
  expect(childBAfter.depth).toBe(0); // Same level as Parent

  // 2. Child B should be AFTER Child D (moved to bottom)
  const childBIndex = after.findIndex(b => b.text === 'Child B');
  const childDIndex = after.findIndex(b => b.text === 'Child D');
  expect(childBIndex).toBeGreaterThan(childDIndex);

  // 3. Child C and D depths UNCHANGED (they moved up visually but depth same)
  expect(childCAfter.depth).toBe(childCBefore.depth);
  expect(childDAfter.depth).toBe(childDBefore.depth);

  // 4. Child C should now be second child of Parent (moved up from third)
  const childCIndexAfter = after.findIndex(b => b.text === 'Child C');
  const childAIndexAfter = after.findIndex(b => b.text === 'Child A');
  expect(childCIndexAfter).toBe(childAIndexAfter + 1);

  // 5. All blocks still visible
  await expect(page.locator('[contenteditable="true"]:has-text("Parent")')).toBeVisible();
  await expect(page.locator('[contenteditable="true"]:has-text("Child A")')).toBeVisible();
  await expect(page.locator('[contenteditable="true"]:has-text("Child B")')).toBeVisible();
  await expect(page.locator('[contenteditable="true"]:has-text("Child C")')).toBeVisible();
  await expect(page.locator('[contenteditable="true"]:has-text("Child D")')).toBeVisible();
});
```

##### ✅ New Test: Direct Outdenting (for comparison)

```javascript
test('direct outdenting moves block right after parent, siblings unchanged', async ({ page }) => {
  // Same setup as above...

  // After outdenting:
  const after = await getTreeStructure(page);
  const childBAfter = after.find(b => b.text === 'Child B');

  // DIRECT OUTDENTING ASSERTIONS:

  // 1. Child B should be outdented
  expect(childBAfter.depth).toBe(0);

  // 2. Child B should be right after Parent (not at bottom)
  const childBIndex = after.findIndex(b => b.text === 'Child B');
  const parentIndex = after.findIndex(b => b.text === 'Parent');
  expect(childBIndex).toBe(parentIndex + 1);

  // 3. Child C and D depths UNCHANGED
  expect(childCAfter.depth).toBe(childCBefore.depth);
  expect(childDAfter.depth).toBe(childDBefore.depth);
});
```

---

## 3. BLOCK DESELECTION

### Current Implementation Status: ⚠️ PARTIAL - Missing Behaviors

**Location:** `src/plugins/selection.cljc`, `src/components/block.cljs`, `src/shell/blocks_ui.cljs`

#### Selection State Management

**Storage:**
```clojure
;; src/plugins/selection.cljc:8
;; Selection stored in session/selection node
{:nodes #{...}      ; Set of selected block IDs
 :focus "block-id"  ; Currently focused block
 :anchor "block-id"} ; Anchor for range selection
```

**Intent:**
```clojure
{:type :selection :mode :clear}
```

#### Current Deselection Methods

##### ✅ Method 1: Click Different Block
**Location:** `src/components/block.cljs:485-489`

```clojure
:on {:click (fn [e]
              (.stopPropagation e)
              (if (.-shiftKey e)
                (on-intent {:type :selection :mode :extend :ids block-id})
                (on-intent {:type :selection :mode :replace :ids block-id})))}
```

**Behavior:** Regular click replaces selection with clicked block.

**Status:** ✅ Matches Logseq

##### ⚠️ Method 2: Escape (Only When Editing)
**Location:** `src/components/block.cljs:258-261`

```clojure
(defn handle-escape [e db block-id on-intent]
  (.preventDefault e)
  (on-intent {:type :exit-edit}))
```

**Problem:** Only exits edit mode, does NOT deselect blocks.

**Logseq Expected:** Escape deselects blocks when NOT editing.

##### ❌ Method 3: Click Empty Space - MISSING
**Location:** `src/shell/blocks_ui.cljs:278`

```clojure
[:div.app
 {:style {:display "flex" :min-height "100vh"}}
 ;; No click handler!
```

**Problem:** Clicking empty space does nothing.

**Logseq Expected:** Click empty space deselects all blocks.

#### Specification: Complete Deselection Behavior

##### Deselection Triggers

| Action | Current Evo | Expected Logseq | Match? |
|--------|-------------|-----------------|--------|
| Click different block | Replace selection | Replace selection | ✅ |
| Click same block (when focused) | Enter edit mode | Enter edit mode | ✅ |
| Escape (while editing) | Exit edit mode | Exit edit mode | ✅ |
| Escape (NOT editing) | Does nothing | Clear selection | ❌ |
| Click empty space | Does nothing | Clear selection | ❌ |
| Click sidebar | Does nothing | Clear selection | ⚠️ (verify) |

#### Implementation Plan

##### Fix 1: Escape Deselects When Not Editing

**File:** `src/shell/blocks_ui.cljs`

**Location:** `handle-global-keydown` function (line ~126)

**Add before existing keymap resolution:**

```clojure
(defn handle-global-keydown [e]
  (let [event (keymap/parse-dom-event e)
        db @!db
        key (.-key e)
        focus-id (q/focus db)
        editing? (q/editing-block-id db)
        intent-type (keymap/resolve-intent-type event db)
        ;; ...existing code...
        ]

    (cond
      ;; NEW: Escape deselects when NOT editing
      (and (= key "Escape")
           (not editing?)
           focus-id)
      (do (.preventDefault e)
          (handle-intent {:type :selection :mode :clear}))

      ;; ... existing cond branches ...
      )))
```

##### Fix 2: Click Empty Space Deselects

**File:** `src/shell/blocks_ui.cljs:278`

**Replace:**
```clojure
[:div.app
 {:style {:display "flex" :min-height "100vh"}}
```

**With:**
```clojure
[:div.app
 {:style {:display "flex" :min-height "100vh"}
  :on {:click (fn [e]
                ;; Only deselect if clicking app background (not child element)
                (when (= (.-target e) (.-currentTarget e))
                  (handle-intent {:type :selection :mode :clear})))}}
```

**Critical:** The `(= (.-target e) (.-currentTarget e))` check ensures we only deselect when clicking the actual app background, NOT when the click bubbles up from a block.

##### Fix 3: Main Content Area Click Deselect

**File:** `src/shell/blocks_ui.cljs:286`

**Also add to `.main-content`:**

```clojure
[:div.main-content
 {:style {:flex "1"
          :margin-left "220px"
          :font-family "system-ui, -apple-system, sans-serif"
          :padding "20px"
          :max-width "800px"}
  :on {:click (fn [e]
                (when (= (.-target e) (.-currentTarget e))
                  (handle-intent {:type :selection :mode :clear})))}}
```

#### Event Propagation Flow

**Current block click behavior:**
```clojure
;; src/components/block.cljs:486
(.stopPropagation e)  ;; Prevents bubbling to parent
```

**This is CRITICAL:** Without `stopPropagation`, every block click would trigger the app/content deselection handler.

**Flow:**
1. User clicks block → Event handled, propagation stopped → Block selected
2. User clicks empty space → Event bubbles to `.app` → Selection cleared

#### Test Plan

##### New E2E Test: Deselection Behaviors

**File:** `test/e2e/selection-deselect.spec.js` (new file)

```javascript
import { test, expect } from '@playwright/test';
import { getAllBlocks } from './helpers/blocks.js';

test.describe('Block Deselection', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/blocks.html');
    await page.waitForSelector('.app');
  });

  test('clicking different block replaces selection', async ({ page }) => {
    const blocks = await page.locator('.block').all();

    // Click first block
    await blocks[0].click();
    await page.waitForTimeout(100);

    // Verify first block selected
    let firstBg = await blocks[0].evaluate(el =>
      window.getComputedStyle(el).backgroundColor
    );
    expect(firstBg).toBe('rgb(179, 217, 255)'); // Focus color

    // Click second block
    await blocks[1].click();
    await page.waitForTimeout(100);

    // Verify only second block selected
    firstBg = await blocks[0].evaluate(el =>
      window.getComputedStyle(el).backgroundColor
    );
    const secondBg = await blocks[1].evaluate(el =>
      window.getComputedStyle(el).backgroundColor
    );

    expect(firstBg).toBe('rgba(0, 0, 0, 0)'); // Transparent
    expect(secondBg).toBe('rgb(179, 217, 255)'); // Focus color
  });

  test('Escape deselects blocks when not editing', async ({ page }) => {
    const blocks = await page.locator('.block').all();

    // Click to select (but don't enter edit mode)
    await blocks[0].click();
    await page.waitForTimeout(100);

    // Verify selected
    let bg = await blocks[0].evaluate(el =>
      window.getComputedStyle(el).backgroundColor
    );
    expect(bg).toBe('rgb(179, 217, 255)');

    // Press Escape (not editing, so should deselect)
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Verify deselected
    bg = await blocks[0].evaluate(el =>
      window.getComputedStyle(el).backgroundColor
    );
    expect(bg).toBe('rgba(0, 0, 0, 0)'); // Transparent
  });

  test('Escape exits edit mode but keeps block selected', async ({ page }) => {
    const blocks = await page.locator('.block').all();

    // Double-click to enter edit mode
    await blocks[0].click();
    await page.waitForTimeout(100);
    await blocks[0].click();
    await page.waitForTimeout(200);

    // Verify editing
    const contentEdit = page.locator('span[contenteditable="true"]');
    await expect(contentEdit).toBeVisible();

    // Press Escape
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);

    // Verify edit mode exited
    await expect(contentEdit).not.toBeVisible();

    // Verify block still selected (focused)
    const bg = await blocks[0].evaluate(el =>
      window.getComputedStyle(el).backgroundColor
    );
    expect(bg).toBe('rgb(179, 217, 255)');
  });

  test('clicking empty space in content area deselects', async ({ page }) => {
    const blocks = await page.locator('.block').all();

    // Select a block
    await blocks[0].click();
    await page.waitForTimeout(100);

    // Verify selected
    let bg = await blocks[0].evaluate(el =>
      window.getComputedStyle(el).backgroundColor
    );
    expect(bg).toBe('rgb(179, 217, 255)');

    // Click empty space in main content area
    // Find a spot with no blocks (below the outline)
    const mainContent = page.locator('.main-content');
    const debugPanel = page.locator('.debug-panel');

    // Click between outline and debug panel
    await mainContent.click({ position: { x: 50, y: 400 } });
    await page.waitForTimeout(100);

    // Verify deselected
    bg = await blocks[0].evaluate(el =>
      window.getComputedStyle(el).backgroundColor
    );
    expect(bg).toBe('rgba(0, 0, 0, 0)');
  });

  test('clicking block does not trigger app click handler', async ({ page }) => {
    // This test ensures .stopPropagation() works

    const blocks = await page.locator('.block').all();

    // Click first block
    await blocks[0].click();
    await page.waitForTimeout(100);

    // Click should select, not deselect
    const bg = await blocks[0].evaluate(el =>
      window.getComputedStyle(el).backgroundColor
    );
    expect(bg).toBe('rgb(179, 217, 255)');
  });
});
```

---

## 4. SUMMARY OF ISSUES

### Critical (P0)

| Issue | Current | Expected | Files Affected |
|-------|---------|----------|----------------|
| **Outdenting behavior** | Right siblings become children | Siblings unchanged, block moves to bottom (logical mode) | `plugins/struct.cljc`, `critical-fixes.spec.js` |

### High Priority (P1)

| Issue | Current | Expected | Files Affected |
|-------|---------|----------|----------------|
| **Escape when not editing** | Does nothing | Deselects blocks | `shell/blocks_ui.cljs` |
| **Click empty space** | Does nothing | Deselects blocks | `shell/blocks_ui.cljs` |

### Medium Priority (P2)

| Issue | Current | Expected | Files Affected |
|-------|---------|----------|----------------|
| **Highlighting verification** | Appears correct | Need manual Logseq verification | `plugins/text_formatting.cljc` |

---

## 5. IMPLEMENTATION CHECKLIST

### Phase 1: Outdenting Fix (P0)

- [ ] **Update `plugins/struct.cljc:51-87`**
  - [ ] Remove right-sibling manipulation code (lines 83-86)
  - [ ] Implement logical outdenting (`:at :last`)
  - [ ] Add config flag for direct vs logical mode
  - [ ] Update docstrings to reflect correct behavior

- [ ] **Rewrite `test/e2e/critical-fixes.spec.js:136-197`**
  - [ ] Delete incorrect test
  - [ ] Add logical outdenting test
  - [ ] Add direct outdenting test (if both modes supported)
  - [ ] Verify siblings depth unchanged
  - [ ] Verify block position change (bottom for logical, after-parent for direct)

- [ ] **Manual verification in Logseq**
  - [ ] Test outdenting with logical mode enabled
  - [ ] Test outdenting with logical mode disabled
  - [ ] Compare visual results with evo

### Phase 2: Deselection Fix (P1)

- [ ] **Update `shell/blocks_ui.cljs`**
  - [ ] Add Escape handler for non-editing deselection (line ~126)
  - [ ] Add click handler to `.app` (line 278)
  - [ ] Add click handler to `.main-content` (line 286)
  - [ ] Test event propagation with existing `stopPropagation`

- [ ] **Create `test/e2e/selection-deselect.spec.js`**
  - [ ] Test Escape deselects when not editing
  - [ ] Test Escape exits edit mode (preserves selection)
  - [ ] Test empty space click deselects
  - [ ] Test block click doesn't trigger parent handlers

### Phase 3: Highlighting Verification (P2)

- [ ] **Manual testing in Logseq**
  - [ ] Verify selection preservation after highlighting
  - [ ] Test toggle on/off
  - [ ] Test edge cases (block boundaries, nested markers)

- [ ] **Add edge case tests if needed**
  - [ ] Overlapping highlights
  - [ ] Highlights at block start/end
  - [ ] Multiple highlights in one block

---

## 6. REFERENCES

### Related Files

```
src/
├── components/
│   └── block.cljs:485-489     (block click handler)
│   └── block.cljs:258-261     (escape handler)
├── plugins/
│   ├── struct.cljc:51-87      (❌ WRONG outdenting)
│   ├── selection.cljc:83-184  (selection state management)
│   └── text_formatting.cljc   (highlighting toggle)
└── shell/
    └── blocks_ui.cljs:278     (app container - needs click handler)
    └── blocks_ui.cljs:99-186  (global keydown handler)

test/
└── e2e/
    ├── critical-fixes.spec.js:136-197  (❌ WRONG test)
    └── selection-deselect.spec.js      (NEW - to be created)
```

### User Configuration

```edn
;; ~/Documents/context/logseq/config.edn:423
:editor/logical-outdenting? true
```

### External References

- [Logseq Discussion: Logical vs Direct Outdenting](https://discuss.logseq.com/t/whats-your-preferred-outdent-behavior-the-direct-one-or-the-logical-one/978)
- [Workflowy 2017 Outdent Behavior Change](https://blog.workflowy.com/)
- Logseq source: `src/main/frontend/modules/shortcut/config.cljs`

---

## 7. TESTING STRATEGY

### Pre-Implementation Testing

Before fixing anything, capture current Logseq behavior:

1. **Screen recording:**
   - Outdent with logical mode ON
   - Outdent with logical mode OFF
   - Escape key in various states
   - Click empty space

2. **State snapshots:**
   - Document tree structure before/after outdenting
   - Selection state before/after deselection triggers

### Post-Implementation Testing

1. **Unit tests:** Selection state calculations
2. **E2E tests:** All new test files listed above
3. **Manual comparison:** Side-by-side evo vs Logseq
4. **Regression tests:** Ensure nothing broke

### Acceptance Criteria

- [ ] Outdenting matches Logseq behavior (logical or direct mode)
- [ ] Right siblings NEVER become children when outdenting
- [ ] Escape deselects blocks when not editing
- [ ] Click empty space deselects blocks
- [ ] Clicking blocks still works (doesn't trigger deselection)
- [ ] All existing tests still pass
- [ ] New tests cover edge cases

---

## 8. RISK ASSESSMENT

### Low Risk
- **Highlighting:** Already mostly correct, minimal changes needed
- **Deselection:** Additive changes, no breaking modifications

### Medium Risk
- **Outdenting:** Behavioral change, but well-isolated function
- **Test rewrites:** Need to verify new tests match Logseq exactly

### High Risk
- **Event propagation:** Click handlers could interfere if not careful
- **Selection state:** Complex interactions between edit mode and selection

### Mitigation Strategy

1. **Incremental rollout:** Fix outdenting first (isolated), then deselection
2. **Feature flags:** Add config for outdenting mode before removing old code
3. **Backup tests:** Keep old tests in separate file until new behavior verified
4. **Manual QA:** Extensive side-by-side comparison with Logseq

---

## 9. OPEN QUESTIONS

1. **Outdenting config:**
   - Should we expose `:editor/logical-outdenting?` setting to users?
   - Or hardcode logical mode since that's what user expects?

2. **Deselection edge cases:**
   - Does clicking sidebar deselect in Logseq?
   - What about clicking debug panel or other UI elements?
   - Should clicking modal/popup backgrounds deselect?

3. **Highlighting:**
   - Does Logseq preserve selection after formatting?
   - What happens with nested highlights `^^outer ^^inner^^ outer^^`?

4. **Migration path:**
   - Any users relying on current "siblings become children" behavior?
   - Need deprecation warning or just fix immediately?

---

## 10. CONCLUSION

Three critical issues identified:

1. **❌ Outdenting:** Completely wrong behavior - implements non-standard "siblings become children" that no major outliner uses
2. **⚠️ Deselection:** Missing two common deselection triggers (Escape, empty click)
3. **✅ Highlighting:** Appears correct but needs verification

**Recommended order:**
1. Fix outdenting (P0) - blocks correct UX
2. Add deselection (P1) - improves UX polish
3. Verify highlighting (P2) - low priority, likely already correct

**Estimated effort:**
- Outdenting fix: 2-3 hours (including tests)
- Deselection fix: 1-2 hours (including tests)
- Highlighting verification: 30 minutes (manual testing)

**Total: ~4-6 hours of focused work**
