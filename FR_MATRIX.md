# FR Coverage Matrix

**Generated:** 2026-04-22T19:26:51.497759

## Summary

- **Total FRs:** 50
- **Complete:** 50 (100%)
- **Missing:** 0

### By type

- **Intent-level:** 43 / 43 complete (needs both an implementing intent and a test citation)
- **Invariants:** 7 / 7 verified (architectural properties — 'complete' ≡ verified by a test; implementation is the code's shape)

## Legend

**Type determines rubric:**

- `intent` (intent-level FR) — complete iff an intent cites it via `:fr/ids` **and** at least one test cites it.
- `invariant` — enforced by the transaction pipeline / state machine / kernel-purity checks, not by any single handler. Complete iff at least one test verifies it. The `Impl` column shows `—` because the concept is inapplicable.

**Status:**
- 🟢 Complete
- 🟡 Implemented-Untested: intent exists, no test citation
- 🟠 Verified-Unimplemented: test exists, no intent citation (intent-level FRs only; rare — usually means TDD got ahead of the handler)
- 🔴 Missing: nothing covers it

**Priority:**
- 🔥 Critical
- ⬆️ High
- ➡️ Medium
- ⬇️ Low

## Coverage Matrix

| FR ID | Type | Priority | Impl | Test | Status | Description |
|-------|------|----------|------|------|--------|-------------|
| backspace-merge | intent | 🔥 critical | ✅ | ✅ | 🟢 complete | Backspace at start merges into previous sibling, re-parents children, preserves caret |
| smart-split | intent | 🔥 critical | ✅ | ✅ | 🟢 complete | Enter performs context-aware split; empty list items unformat + create peer in single step |
| derive-indexes | invariant | 🔥 critical | — | ✅ | 🟢 complete | All operations trigger automatic index derivation; never mutate derived state directly |
| undo-restores-all | invariant | 🔥 critical | — | ✅ | 🟢 complete | Undo/redo restores block content + caret position + selection state |
| vertical-cursor-memory | intent | 🔥 critical | ✅ | ✅ | 🟢 complete | Vertical navigation preserves horizontal grapheme column across blocks |
| view-arrows | intent | 🔥 critical | ✅ | ✅ | 🟢 complete | ArrowUp/Down in view mode moves focus through visible siblings, skipping folded blocks |
| edit-view-exclusive | invariant | 🔥 critical | — | ✅ | 🟢 complete | Edit mode and selection state never coexist; transitions clear opposite state |
| extend-boundary | intent | 🔥 critical | ✅ | ✅ | 🟢 complete | Shift+Arrow extends selection incrementally with direction tracking |
| indent-outdent | intent | 🔥 critical | ✅ | ✅ | 🟢 complete | Tab/Shift+Tab moves blocks under previous sibling / after parent; guards zoom roots |
| copy-block | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Cmd+C copies selected blocks with metadata |
| paste-multiline | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Pasting multi-line text creates multiple blocks |
| arrow-nav-mode | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Arrow keys move cursor within block in Edit Mode |
| delete-forward | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Delete key removes character at cursor or merges with next block at end |
| newline-no-split | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Shift+Enter inserts newline without splitting block |
| shift-arrow-text-select | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Shift+Arrow extends text selection within block (seeds multi-block selection) |
| horizontal-boundary | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | ArrowLeft at column 0 / ArrowRight at end jumps to adjacent block |
| idle-first-last | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | ArrowDown/Up from idle state selects first/last visible block |
| follow-link | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Cmd+Click or Cmd+O on page reference navigates to that page |
| switch-page | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Navigate to different page (clears zoom, preserves page state) |
| visible-outline | invariant | ⬆️ high | — | ✅ | 🟢 complete | Navigation stays within visible outline (respects page, zoom, folding) |
| cmd-a-cycle | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Cmd+A cycles: select all text → select block → select parent → select all visible |
| list-unformat | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Enter on empty list item removes marker and creates peer at parent level |
| idle-guard | invariant | ⬆️ high | — | ✅ | 🟢 complete | Idle state requires explicit action to enter edit/selection; prevents accidental state changes |
| selection-clears-edit | invariant | ⬆️ high | — | ✅ | 🟢 complete | Entering selection mode clears editing state |
| type-to-edit | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Typing from idle/selection enters edit mode on first/selected block |
| climb-descend | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Cmd+Shift+Up/Down at boundaries climbs out to parent level / descends into next sibling |
| create-sibling | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Create new block as sibling of current/selected |
| delete-block | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Delete selected blocks (children move to trash with parent) |
| quick-switcher | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Cmd+K opens global search overlay |
| slash-palette | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Slash (/) opens inline command menu |
| slash-select | intent | ⬆️ high | ✅ | ✅ | 🟢 complete | Enter inserts the selected slash command |
| block-reference | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Cmd+Shift+C copies block reference ((id)) |
| kill-operations | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Ctrl+K/U kill to end/beginning of line |
| paired-char-insertion | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Auto-insert closing bracket/quote and place cursor inside |
| word-navigation | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Option+Arrow moves cursor by word boundaries |
| toggle-block | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Cmd+Up/Down toggles fold state of current block |
| bold-italic | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Cmd+B/I wraps selection in **bold** or *italic* markers |
| alt-click-fold | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Alt+Click toggles fold state of subtree |
| shift-click-range | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Shift+Click extends selection range from current to clicked block |
| zoom-guards | invariant | ➡️ medium | — | ✅ | 🟢 complete | Structural operations blocked by zoom boundary (can't indent/outdent zoom root) |
| checkbox-toggle | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Cmd+Enter toggles TODO/DOING/DONE markers |
| slash-close | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Esc closes slash palette without side effects |
| slash-filter | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Slash palette search filters commands incrementally |
| slash-navigate | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Arrow keys move slash selection with wraparound |
| focus-subtree | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Cmd+. zooms into block as temporary root |
| restore-scope | intent | ➡️ medium | ✅ | ✅ | 🟢 complete | Escape from zoom restores normal scope |
| expand-collapse-all | intent | ⬇️ low | ✅ | ✅ | 🟢 complete | Cmd+Shift+Up/Down expands/collapses all blocks in scope |
| highlight-strikethrough | intent | ⬇️ low | ✅ | ✅ | 🟢 complete | Cmd+H/Shift+H wraps selection in ^^highlight^^ or ~~strikethrough~~ |
| grapheme-cursor-memory | intent | ⬇️ low | ✅ | ✅ | 🟢 complete | Cursor column memory uses grapheme clusters (not UTF-16 code units) for proper emoji/CJK handling |
| auto-increment | intent | ⬇️ low | ✅ | ✅ | 🟢 complete | Enter on numbered list auto-increments next item |
